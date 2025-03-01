import chisel3._
import chisel3.util._
import utils._


class StoreNegAck extends CoreBundle {
  val stqPtr = RingBufferPtr(STQ_SIZE)
}

class StoreQueueIO extends CoreBundle {
  val IN_AGUUop       = Flipped(Decoupled(new AGUUop))
  val IN_negAck       = Flipped(Valid(new StoreNegAck))
  val IN_robTailPtr   = Flipped(RingBufferPtr(ROB_SIZE))
  val IN_commitStqPtr = Flipped(RingBufferPtr(STQ_IDX_W.get))
  val OUT_stUop       = Valid(new AGUUop)

  val IN_storeBypassReq = Flipped(new StoreBypassReq)
  val OUT_storeBypassResp = new StoreBypassResp
}

class StoreQueue extends CoreModule {
  val io = IO(new StoreQueueIO)

  val stq       = Reg(Vec(STQ_SIZE, new AGUUop))
  val stqValid  = RegInit(VecInit(Seq.fill(STQ_SIZE)(false.B)))
  val stqIssued = RegInit(VecInit(Seq.fill(STQ_SIZE)(false.B)))
  val committed  = RegInit(VecInit(Seq.fill(STQ_SIZE)(false.B)))

  val hasStqValid     = stqValid.reduce(_ || _)
  val hasStqNotIssued = stqIssued.map(~_).reduce(_ || _)

  val uop      = Reg(new AGUUop)
  val uopValid = RegInit(false.B)

  // Enqueue logic
  when(io.IN_AGUUop.fire && LSUOp.isStore(io.IN_AGUUop.bits.opcode)) {
    stq(io.IN_AGUUop.bits.stqPtr.index)       := io.IN_AGUUop.bits
    stqValid(io.IN_AGUUop.bits.stqPtr.index)  := true.B
    stqIssued(io.IN_AGUUop.bits.stqPtr.index) := false.B
    committed(io.IN_AGUUop.bits.stqPtr.index)  := false.B
  }

  def isInRange(index: UInt, start: RingBufferPtr, end: RingBufferPtr): Bool = {
    val wrap    = end.flag =/= start.flag
    val inRange = Mux(wrap, index >= start.index || index < end.index, index >= start.index && index < end.index)
    inRange
  }

  // Invalidate on commit
  val lastCommitStqPtr = RegNext(io.IN_commitStqPtr)
  val newCommitted = VecInit((0 until STQ_SIZE).map { i =>
    val index = i.U(STQ_IDX_W)
    isInRange(index, lastCommitStqPtr, io.IN_commitStqPtr)
  })

  newCommitted.zipWithIndex.foreach {
    case (inv, i) =>
      when(inv) {
        committed(i) := true.B
      }
  }

  def getWmask(aguUop: AGUUop): UInt = {
    val memLen = aguUop.opcode(2, 1)
    val addrOffset = aguUop.addr(log2Up(XLEN/8) - 1, 0)
    val wmask = MuxLookup(memLen, 0.U(4.W))(
      Seq(
        0.U(2.W) -> "b0001".U,
        1.U(2.W) -> "b0011".U,
        2.U(2.W) -> "b1111".U
      )
    ) << addrOffset
    wmask
  }

  def addrMatch (addr1: UInt, addr2: UInt): Bool = {
    addr1(XLEN - 1, 2) === addr2(XLEN - 1, 2)
  }

  // * Store bypass logic
  val bypassDataNext = Wire(Vec(4, UInt(8.W)))
  bypassDataNext := 0.U.asTypeOf(bypassDataNext)
  val bypassDataMaskNext = Wire(Vec(4, Bool()))
  bypassDataMaskNext := 0.U.asTypeOf(bypassDataMaskNext)
  val bypassData = RegNext(bypassDataNext)
  val bypassDataMask = RegNext(bypassDataMaskNext)
  io.OUT_storeBypassResp.data := bypassData.asTypeOf(UInt(32.W))
  io.OUT_storeBypassResp.mask := bypassDataMask.asUInt
  val wmask = stq.map(getWmask(_))
  when(lastCommitStqPtr.flag === io.IN_storeBypassReq.stqPtr.flag) {
    for (i <- 0 until LDQ_SIZE) {
      when(addrMatch(io.IN_storeBypassReq.addr, stq(i).addr) && stqValid(i)) {
        for (j <- 0 until 4) {
          when(wmask(i)(j)) {
            bypassDataNext(j) := stq(i).wdata((j + 1) * 8 - 1, j * 8)
            bypassDataMaskNext(j) := true.B
          }
        }
      }
    }
  }.otherwise {
    for (i <- 0 until LDQ_SIZE) {
      when(addrMatch(io.IN_storeBypassReq.addr, stq(i).addr) && i.U >= lastCommitStqPtr.index && stqValid(i)) {
        for (j <- 0 until 4) {
          when(wmask(i)(j)) {
            bypassDataNext(j) := stq(i).wdata((j + 1) * 8 - 1, j * 8)
            bypassDataMaskNext(j) := true.B
          }
        }
      }
    }
    for (i <- 0 until LDQ_SIZE) {
      when(addrMatch(io.IN_storeBypassReq.addr, stq(i).addr) && i.U < io.IN_storeBypassReq.stqPtr.index && stqValid(i)) {
        for (j <- 0 until 4) {
          when(wmask(i)(j)) {
            bypassDataNext(j) := stq(i).wdata((j + 1) * 8 - 1, j * 8)
            bypassDataMaskNext(j) := true.B
          }
        }
      }
    }
  }

  // Issue logic
  val issueReady    = stqValid.asUInt & ~(stqIssued.asUInt) & (committed.asUInt)
  val hasIssueReady = issueReady.orR
  val stqIssueIndex = PriorityEncoder(issueReady)

  uopValid := false.B
  when(hasIssueReady) {
    uop                      := stq(stqIssueIndex)
    uopValid                 := true.B
    stqIssued(stqIssueIndex) := true.B
  }

  when(io.IN_negAck.valid) {
    stqIssued(io.IN_negAck.bits.stqPtr.index) := false.B
  }

  io.OUT_stUop.valid := uopValid
  io.OUT_stUop.bits  := uop

  io.IN_AGUUop.ready := true.B
}
