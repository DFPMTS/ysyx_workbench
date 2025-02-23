import chisel3._
import chisel3.util._
import utils._

class LoadUop extends CoreBundle {
  val prd  = UInt(PREG_IDX_W)

  val addr = UInt(XLEN.W)
  val wdata = UInt(XLEN.W)

  val dest = UInt(1.W)

  val robPtr = RingBufferPtr(ROB_SIZE)
  val ldqIndex = UInt(LDQ_IDX_W)
  val stqIndex = UInt(STQ_IDX_W)

  val fuType = UInt(FuTypeWidth.W)
  val opcode = UInt(OpcodeWidth.W)
}

class LdNegAck extends CoreBundle {
  val ldqPtr = RingBufferPtr(LDQ_IDX_W.get)
}

class LoadQueueIO extends CoreBundle {
  val IN_AGUUop = Flipped(Decoupled(new AGUUop))
  val IN_negAck = Input(Valid(new LdNegAck))
  val IN_robTailPtr = Input(RingBufferPtr(ROB_SIZE))
  val IN_commitLdqPtr = Input(RingBufferPtr(LDQ_IDX_W.get))
  val OUT_ldUop = Valid(new AGUUop)
}

class LoadQueue extends CoreModule {
  val io = IO(new LoadQueueIO)
  
  val ldq = Reg(Vec(LDQ_SIZE, new AGUUop))
  val ldqValid = RegInit(VecInit(Seq.fill(LDQ_SIZE)(false.B)))
  val ldqIssued = RegInit(VecInit(Seq.fill(LDQ_SIZE)(false.B)))

  val hasLdqValid = ldqValid.reduce(_ || _)
  val hasLdqNotIssued = ldqIssued.map(~_).reduce(_ || _)

  val uop = Reg(new LoadUop)
  val uopValid = RegInit(false.B)

  // * enqueue
  when(io.IN_AGUUop.fire) {
    ldq(io.IN_AGUUop.bits.ldqPtr.index) := io.IN_AGUUop.bits
    ldqValid(io.IN_AGUUop.bits.ldqPtr.index) := true.B
    ldqIssued(io.IN_AGUUop.bits.ldqPtr.index) := false.B
  }

  // * choose
  val issueReady = VecInit(ldq.map(_.robPtr === io.IN_robTailPtr)).asUInt & ldqValid.asUInt & ~(ldqIssued.asUInt)
  val hasIssueReady = issueReady.orR
  val ldqIssueIndex = PriorityEncoder(issueReady)

  def isInRange(index: UInt, start: RingBufferPtr, end: RingBufferPtr): Bool = {
    val wrap = end.flag =/= start.flag
    val inRange = Mux(wrap, index >= start.index || index < end.index, index >= start.index && index < end.index)
    inRange
  }

  // * invalidate on commit
  val lastCommitLdqPtr = RegNext(io.IN_commitLdqPtr)
  val invalidateOnCommit = VecInit((0 until LDQ_SIZE).map { i =>
    val index = i.U(LDQ_IDX_W)
    isInRange(index, lastCommitLdqPtr, io.IN_commitLdqPtr)
  })

  invalidateOnCommit.zipWithIndex.foreach { case (inv, i) =>
    when(inv) {
      ldqValid(i) := false.B
    }
  }

  // * issue logic
  when(io.IN_negAck.valid) {
    ldqIssued(io.IN_negAck.bits.ldqPtr.index) := false.B
  }

  uopValid := false.B
  when(hasIssueReady) {
    uop := ldq(ldqIssueIndex)
    uopValid := true.B
    ldqIssued(ldqIssueIndex) := true.B
  }

  io.OUT_ldUop.valid := uopValid
  io.OUT_ldUop.bits := uop

  io.IN_AGUUop.ready := true.B
}