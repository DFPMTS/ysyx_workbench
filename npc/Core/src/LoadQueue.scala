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
  val IN_AGUUop = Flipped(Valid(new AGUUop))
  val IN_negAck = Input(Valid(new LoadNegAck))
  val IN_robTailPtr = Input(RingBufferPtr(ROB_SIZE))
  val IN_commitLdqPtr = Input(RingBufferPtr(LDQ_SIZE))
  val IN_commitStqPtr = Input(RingBufferPtr(STQ_SIZE))
  val OUT_ldUop = Decoupled(new AGUUop)

  val IN_flush = Flipped(Bool())
}

class LoadQueue extends CoreModule {
  val io = IO(new LoadQueueIO)
  
  val ldq = Reg(Vec(LDQ_SIZE, new AGUUop))
  val ldqValid = RegInit(VecInit(Seq.fill(LDQ_SIZE)(false.B)))
  val ldqIssued = RegInit(VecInit(Seq.fill(LDQ_SIZE)(false.B)))
  // * Is all store before this load finished?
  // val ldqReady = RegInit(VecInit(Seq.fill(LDQ_SIZE)(false.B)))

  val hasLdqValid = ldqValid.reduce(_ || _)
  val hasLdqNotIssued = ldqIssued.map(~_).reduce(_ || _)

  val uop = Reg(new AGUUop)
  val uopValid = RegInit(false.B)

  def storeCommited(stqPtr: RingBufferPtr, commitStqPtr: RingBufferPtr) = {
    val flagDiff = stqPtr.flag ^ commitStqPtr.flag
    val indexLeq = stqPtr.index <= commitStqPtr.index
    val indexGeq = stqPtr.index >= commitStqPtr.index
    ((flagDiff & indexGeq) | (~flagDiff & indexLeq)).asBool
  }

  // * enqueue
  when(io.IN_AGUUop.fire && LSUOp.isLoad(io.IN_AGUUop.bits.opcode)) {
    ldq(io.IN_AGUUop.bits.ldqPtr.index) := io.IN_AGUUop.bits
    ldqValid(io.IN_AGUUop.bits.ldqPtr.index) := true.B
    ldqIssued(io.IN_AGUUop.bits.ldqPtr.index) := false.B
    // ldqReady(io.IN_AGUUop.bits.ldqPtr.index) := storeCommited(io.IN_AGUUop.bits.stqPtr, io.IN_commitStqPtr)
  }

  val ldqReady = VecInit(ldq.zipWithIndex.map{ 
    case (uop, index) => 
      storeCommited(uop.stqPtr, io.IN_commitStqPtr) && (!Addr.isUncached(uop.addr) || io.IN_robTailPtr.index === uop.robPtr.index)
  })
  // * choose
  val issueReady = ldqValid.asUInt & ~(ldqIssued.asUInt) & ldqReady.asUInt
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
  when(io.OUT_ldUop.ready || !uopValid) {
    uop := ldq(ldqIssueIndex)
    uopValid := hasIssueReady
    when(hasIssueReady) {
      ldqIssued(ldqIssueIndex) := true.B
    }
  }

  when(io.IN_negAck.valid && io.IN_negAck.bits.dest =/= Dest.PTW) {
    ldqIssued(io.IN_negAck.bits.ldqPtr.index) := false.B
  }

  when(io.IN_flush) {
    ldqValid := VecInit(Seq.fill(LDQ_SIZE)(false.B))
    uopValid := false.B
  }

  io.OUT_ldUop.valid := uopValid
  io.OUT_ldUop.bits := uop
}