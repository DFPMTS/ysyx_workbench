import chisel3._
import chisel3.util._
import utils._


class StoreQueueIO extends CoreBundle {
  val IN_AGUUop       = Flipped(Valid(new AGUUop))
  val IN_storeData    = Flipped(Valid(new StoreData))
  val IN_robTailPtr   = Flipped(RingBufferPtr(ROB_SIZE))
  val IN_commitStqPtr = Flipped(RingBufferPtr(STQ_SIZE))
  val OUT_stUop       = Decoupled(new AGUUop)
  val OUT_stqBasePtr  = RingBufferPtr(STQ_SIZE)
  val OUT_storeQueueEmpty = Bool()

  val OUT_stqLoadLimitPtr = RingBufferPtr(STQ_SIZE)

  val IN_storeBypassReq = Flipped(new StoreBypassReq)
  val OUT_storeBypassResp = new StoreBypassResp
  val IN_flush = Flipped(Bool())
  val IN_flushStqPtr = Flipped(RingBufferPtr(STQ_SIZE))
}

class StoreQueue extends CoreModule {
  val io = IO(new StoreQueueIO)

  val stq       = Reg(Vec(STQ_SIZE, new AGUUop))
  val stqValid  = RegInit(VecInit(Seq.fill(STQ_SIZE)(false.B)))
  val stqDataReady = RegInit(VecInit(Seq.fill(STQ_SIZE)(false.B)))
  // val committed  = RegInit(VecInit(Seq.fill(STQ_SIZE)(false.B)))

  val hasStqValid     = stqValid.reduce(_ || _)

  val uop      = Reg(new AGUUop)
  val uopValid = RegInit(false.B)

  // Enqueue logic
  when(io.IN_AGUUop.fire && (io.IN_AGUUop.bits.fuType === FuType.LSU && LSUOp.isStore(io.IN_AGUUop.bits.opcode))) {
    stq(io.IN_AGUUop.bits.stqPtr.index)       := io.IN_AGUUop.bits
    stqValid(io.IN_AGUUop.bits.stqPtr.index)  := true.B
    // committed(io.IN_AGUUop.bits.stqPtr.index)  := false.B
    stqDataReady(io.IN_AGUUop.bits.stqPtr.index) := false.B
  }

  when(io.IN_storeData.valid) {
    stq(io.IN_storeData.bits.stqPtr.index).wdata := io.IN_storeData.bits.data
    stqDataReady(io.IN_storeData.bits.stqPtr.index) := true.B
  }

  def isInRange(index: UInt, start: RingBufferPtr, end: RingBufferPtr): Bool = {
    val wrap    = end.flag =/= start.flag
    val inRange = Mux(wrap, index >= start.index || index < end.index, index >= start.index && index < end.index)
    inRange
  }

  // * stqPtr base Ptr
  val stqBasePtr = RegInit(RingBufferPtr(STQ_SIZE, 0.U, 0.U))
  io.OUT_stqBasePtr := stqBasePtr
  
  // * storeQueueLimitPtr
  val stqLoadLimitPtr = RegInit(RingBufferPtr(STQ_SIZE, 0.U, 0.U))
  io.OUT_stqLoadLimitPtr := stqLoadLimitPtr

  val stqLimitPtr = Wire(RingBufferPtr(STQ_SIZE))
  stqLimitPtr.flag := ~stqBasePtr.flag
  stqLimitPtr.index := stqBasePtr.index

  when(stqValid(stqLoadLimitPtr.index) && (stqLoadLimitPtr + 1.U).isBefore(stqLimitPtr)) {
    stqLoadLimitPtr := stqLoadLimitPtr + 1.U
  }

  // val newCommitted = VecInit((0 until STQ_SIZE).map { i =>
  //   val index = i.U(STQ_IDX_W)
  //   isInRange(index, lastCommitStqPtr, io.IN_commitStqPtr)
  // })

  // newCommitted.zipWithIndex.foreach {
  //   case (inv, i) =>
  //     when(inv) {
  //       committed(i) := true.B
  //     }
  // }

  def addrMatch (addr1: UInt, addr2: UInt): Bool = {
    addr1(XLEN - 1, 2) === addr2(XLEN - 1, 2)
  }

  // * Store bypass logic
  val bypassDataNext = Wire(Vec(4, UInt(8.W)))
  bypassDataNext := 0.U.asTypeOf(bypassDataNext)
  val bypassDataMaskNext = Wire(Vec(4, Bool()))
  bypassDataMaskNext := 0.U.asTypeOf(bypassDataMaskNext)
  val bypassDataNotReady = Wire(Vec(4, Bool()))
  bypassDataNotReady := 0.U.asTypeOf(bypassDataNotReady)
  // val bypassData = RegNext(bypassDataNext)
  // val bypassDataMask = RegNext(bypassDataMaskNext)
  val bypassData = bypassDataNext
  val bypassDataMask = bypassDataMaskNext
  io.OUT_storeBypassResp.data := bypassData.asTypeOf(UInt(32.W))
  io.OUT_storeBypassResp.mask := bypassDataMask.asUInt
  io.OUT_storeBypassResp.notReady := bypassDataNotReady.asUInt
  val wmask = stq.map(_.mask)
  def getShiftedData(aguUop: AGUUop): UInt = {
    val addrOffset = aguUop.addr(log2Up(XLEN/8) - 1, 0)
    (aguUop.wdata << (addrOffset << 3))(XLEN - 1, 0)
  }
  val shiftedData = stq.map(e => getShiftedData(e))

  when(addrMatch(io.IN_storeBypassReq.addr, uop.addr) && uopValid) {
    val uopWmask = uop.mask
    val uopShiftedData = uop.wdata
    dontTouch(uopShiftedData)
    for (i <- 0 until 4) {
      when(uopWmask(i)) {
        bypassDataNext(i) := uopShiftedData((i + 1) * 8 - 1, i * 8)
        bypassDataMaskNext(i) := true.B
      }
    }
  }

  when(stqBasePtr.flag === io.IN_storeBypassReq.stqPtr.flag) {
    for (i <- 0 until STQ_SIZE) {
      when(addrMatch(io.IN_storeBypassReq.addr, stq(i).addr) && stqValid(i)
         && i.U >= stqBasePtr.index && i.U < io.IN_storeBypassReq.stqPtr.index) {
        for (j <- 0 until 4) {
          when(wmask(i)(j)) {
            bypassDataNext(j) := shiftedData(i)((j + 1) * 8 - 1, j * 8)
            bypassDataMaskNext(j) := true.B
            bypassDataNotReady(j) := !stqDataReady(i)
          }
        }
      }
    }
  }.elsewhen(stqBasePtr.flag =/= io.IN_storeBypassReq.stqPtr.flag) {
    for (i <- 0 until STQ_SIZE) {
      when(addrMatch(io.IN_storeBypassReq.addr, stq(i).addr) && i.U >= stqBasePtr.index && stqValid(i)) {
        for (j <- 0 until 4) {
          when(wmask(i)(j)) {
            bypassDataNext(j) := shiftedData(i)((j + 1) * 8 - 1, j * 8)
            bypassDataMaskNext(j) := true.B
            bypassDataNotReady(j) := !stqDataReady(i)
          }
        }
      }
    }
    for (i <- 0 until STQ_SIZE) {
      when(addrMatch(io.IN_storeBypassReq.addr, stq(i).addr) && i.U < io.IN_storeBypassReq.stqPtr.index && stqValid(i)) {
        for (j <- 0 until 4) {
          when(wmask(i)(j)) {
            bypassDataNext(j) := shiftedData(i)((j + 1) * 8 - 1, j * 8)
            bypassDataMaskNext(j) := true.B
            bypassDataNotReady(j) := !stqDataReady(i)
          }
        }
      }
    }
  }

  // Issue logic
  val stqIssueIndex = stqBasePtr.index
  val hasIssueReady = stqBasePtr.isBefore(io.IN_commitStqPtr) && stqDataReady(stqIssueIndex)

  when(io.OUT_stUop.ready || !uopValid) {    
    uop := stq(stqIssueIndex)
    uop.wdata := getShiftedData(stq(stqIssueIndex))
    uopValid := hasIssueReady
    when(hasIssueReady) {
      stqBasePtr := stqBasePtr + 1.U
      stqDataReady(stqIssueIndex) := false.B
      stqValid(stqIssueIndex) := false.B
    }
  }

  when(io.IN_flush) {
    stqLoadLimitPtr := io.IN_commitStqPtr
    when(io.IN_commitStqPtr.flag =/= stqBasePtr.flag) {
      for (i <- 0 until STQ_SIZE) {
        when(i.U < stqBasePtr.index && i.U >= io.IN_flushStqPtr.index) {
          stqValid(i) := false.B
          stqDataReady(i) := false.B
        }
      }
    }.otherwise{
      for (i <- 0 until STQ_SIZE) {
        when(i.U < stqBasePtr.index || i.U >= io.IN_flushStqPtr.index) {
          stqValid(i) := false.B
          stqDataReady(i) := false.B
        }
      }
    }
  }

  io.OUT_storeQueueEmpty := !hasIssueReady && !uopValid
  io.OUT_stUop.valid := uopValid
  io.OUT_stUop.bits  := uop
}
