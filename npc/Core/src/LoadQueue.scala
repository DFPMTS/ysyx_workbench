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

class LoadNegAck extends CoreBundle {
  val ldqPtr = RingBufferPtr(LDQ_SIZE)
  val dest = UInt(1.W) // 0: LoadQueue 1: PTW
}

class LoadQueueIO extends CoreBundle {
  val IN_AGUUop = Flipped(Decoupled(new AGUUop))
  val IN_negAck = Input(Valid(new LoadNegAck))
  val IN_robTailPtr = Input(RingBufferPtr(ROB_SIZE))
  val IN_commitLdqPtr = Input(RingBufferPtr(LDQ_IDX_W.get))
  val OUT_ldUop = Decoupled(new AGUUop)
}

class LoadQueue extends CoreModule {
  val io = IO(new LoadQueueIO)
  
  val ldq = Reg(Vec(LDQ_SIZE, new AGUUop))
  val ldqValid = RegInit(VecInit(Seq.fill(LDQ_SIZE)(false.B)))
  val ldqIssued = RegInit(VecInit(Seq.fill(LDQ_SIZE)(false.B)))

  val hasLdqValid = ldqValid.reduce(_ || _)
  val hasLdqNotIssued = ldqIssued.map(~_).reduce(_ || _)

  val uop = Reg(new AGUUop)
  val uopValid = RegInit(false.B)

  // * enqueue
  when(io.IN_AGUUop.fire) {
    ldq(io.IN_AGUUop.bits.ldqPtr.index) := io.IN_AGUUop.bits
    ldqValid(io.IN_AGUUop.bits.ldqPtr.index) := true.B
    ldqIssued(io.IN_AGUUop.bits.ldqPtr.index) := false.B
  }

  // * choose
  val issueReady = VecInit(ldq.map(_.robPtr.index === io.IN_robTailPtr.index)).asUInt & ldqValid.asUInt & ~(ldqIssued.asUInt)
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
  uopValid := false.B
  when(io.OUT_ldUop.ready || !uopValid) {
    uop := ldq(ldqIssueIndex)
    uopValid := hasIssueReady
    ldqIssued(ldqIssueIndex) := true.B
  }

  when(io.IN_negAck.valid && io.IN_negAck.bits.dest =/= Dest.PTW) {
    ldqIssued(io.IN_negAck.bits.ldqPtr.index) := false.B
  }

  io.OUT_ldUop.valid := uopValid
  io.OUT_ldUop.bits := uop

  io.IN_AGUUop.ready := true.B
}