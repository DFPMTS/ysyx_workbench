import chisel3._
import chisel3.util._
import utils._


class StNegAck extends CoreBundle {
  val stqPtr = RingBufferPtr(STQ_IDX_W.get)
}

class StoreQueueIO extends CoreBundle {
  val IN_AGUUop       = Flipped(Decoupled(new AGUUop))
  val IN_negAck       = Input(Valid(new StNegAck))
  val IN_commitStqPtr = Input(RingBufferPtr(STQ_IDX_W.get))
  val OUT_stUop       = Valid(new AGUUop)
}

class StoreQueue extends CoreModule {
  val io = IO(new StoreQueueIO)

  val stq       = Reg(Vec(STQ_SIZE, new AGUUop))
  val stqValid  = RegInit(VecInit(Seq.fill(STQ_SIZE)(false.B)))
  val stqIssued = RegInit(VecInit(Seq.fill(STQ_SIZE)(false.B)))

  val hasStqValid     = stqValid.reduce(_ || _)
  val hasStqNotIssued = stqIssued.map(~_).reduce(_ || _)

  val uop      = Reg(new AGUUop)
  val uopValid = RegInit(false.B)

  // Enqueue logic
  when(io.IN_AGUUop.fire && LSUOp.isStore(io.IN_AGUUop.bits.opcode)) {
    stq(io.IN_AGUUop.bits.stqPtr.index)       := io.IN_AGUUop.bits
    stqValid(io.IN_AGUUop.bits.stqPtr.index)  := true.B
    stqIssued(io.IN_AGUUop.bits.stqPtr.index) := false.B
  }

  def isInRange(index: UInt, start: RingBufferPtr, end: RingBufferPtr): Bool = {
    val wrap    = end.flag =/= start.flag
    val inRange = Mux(wrap, index >= start.index || index < end.index, index >= start.index && index < end.index)
    inRange
  }

  // Invalidate on commit
  val lastCommitStqPtr = RegNext(io.IN_commitStqPtr)
  val invalidateOnCommit = VecInit((0 until STQ_SIZE).map { i =>
    val index = i.U(STQ_IDX_W)
    isInRange(index, lastCommitStqPtr, io.IN_commitStqPtr)
  })

  invalidateOnCommit.zipWithIndex.foreach {
    case (inv, i) =>
      when(inv) {
        stqValid(i) := false.B
      }
  }

  // Issue logic
  when(io.IN_negAck.valid) {
    stqIssued(io.IN_negAck.bits.stqPtr.index) := false.B
  }

  val issueReady    = stqValid.asUInt & ~(stqIssued.asUInt)
  val hasIssueReady = issueReady.orR
  val stqIssueIndex = PriorityEncoder(issueReady)

  uopValid := false.B
  when(hasIssueReady) {
    uop                      := stq(stqIssueIndex)
    uopValid                 := true.B
    stqIssued(stqIssueIndex) := true.B
  }

  io.OUT_stUop.valid := uopValid
  io.OUT_stUop.bits  := uop

  io.IN_AGUUop.ready := true.B
}
