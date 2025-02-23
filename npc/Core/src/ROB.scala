import chisel3._
import chisel3.util._
import utils._

class ROBIO extends CoreBundle {
  val IN_renameUop = Flipped(Vec(ISSUE_WIDTH, Valid(new RenameUop)))
  val OUT_renameUopReady = Bool()
  val IN_writebackUop = Flipped(Vec(MACHINE_WIDTH, Valid(new WritebackUop)))
  val OUT_commitUop = Vec(COMMIT_WIDTH, Valid(new CommitUop))  
  val OUT_robTailPtr = Output(RingBufferPtr(ROB_SIZE))
  val OUT_ldqTailPtr = Output(RingBufferPtr(LDQ_SIZE))
  val OUT_stqTailPtr = Output(RingBufferPtr(STQ_SIZE))
  val IN_renameRobHeadPtr = Input(RingBufferPtr(ROB_SIZE))
  val OUT_flagUop = Valid(new FlagUop)

  val IN_flush = Input(Bool())
}

class ROBEntry extends CoreBundle {
  val rd   = UInt(5.W)
  val prd  = UInt(PREG_IDX_W)
  val flag = UInt(FLAG_W)
  val executed = Bool()
  val pc   = UInt(XLEN.W)
  // * temporary
  val target = UInt(XLEN.W)

  val isLoad = Bool()
  val isStore = Bool()
}

class ROB extends CoreModule {
  val io = IO(new ROBIO)
  
  val rob = Reg(Vec(ROB_SIZE, new ROBEntry))
  val robStall = RegInit(false.B)

  // ** ROB head/tail
  val robHeadPtr = RegInit(RingBufferPtr(size = ROB_SIZE, flag = 0.U, index = 0.U))  
  val robTailPtr = RegInit(RingBufferPtr(size = ROB_SIZE, flag = 1.U, index = 0.U))

  // ** Ldq/Stq tail
  val ldqTailPtr = RegInit(RingBufferPtr(size = LDQ_SIZE, flag = 1.U, index = 0.U))
  val stqTailPtr = RegInit(RingBufferPtr(size = STQ_SIZE, flag = 1.U, index = 0.U))

  // ** Control
  val enqStall = false.B // * ROB stall is handled in Rename stage
  for (i <- 0 until ISSUE_WIDTH) {
    io.OUT_renameUopReady := !enqStall
  }

  // ** enqueue
  for (i <- 0 until ISSUE_WIDTH) {
    val renameUop = io.IN_renameUop(i).bits
    val enqEntry = Wire(new ROBEntry)
    enqEntry.rd := renameUop.rd
    enqEntry.prd := renameUop.prd
    enqEntry.flag := Mux(renameUop.fuType === FuType.FLAG, renameUop.opcode, FlagOp.NONE)
    enqEntry.pc  := renameUop.pc
    enqEntry.executed := renameUop.fuType === FuType.FLAG
    enqEntry.target := 0.U
    enqEntry.isLoad := renameUop.fuType === FuType.LSU && LSUOp.isLoad(renameUop.opcode)
    enqEntry.isStore := renameUop.fuType === FuType.LSU && LSUOp.isStore(renameUop.opcode)
    
    when (io.IN_renameUop(i).fire) {
      rob(io.IN_renameUop(i).bits.robPtr.index) := enqEntry
    }
  }

  // ** dequeue (Commit)
  val commitUop = Reg(Vec(COMMIT_WIDTH, new CommitUop))
  val commitValid = RegInit(VecInit(Seq.fill(COMMIT_WIDTH)(false.B)))

  val flagUopNextValid = Wire(Bool())
  val flagUopNext = Wire(new FlagUop)
  val flagUopValid = RegInit(false.B)
  val flagUop     = Reg(new FlagUop)

  val deqEntry = Wire(Vec(COMMIT_WIDTH, new ROBEntry))
  val deqValid = Wire(Vec(COMMIT_WIDTH, Bool()))

  for (i <- 0 until COMMIT_WIDTH) {
    val deqPtr = robTailPtr + i.U
    val distance = robHeadPtr.distanceTo(deqPtr)
    deqEntry(i) := rob(deqPtr.index)
    deqValid(i) := robHeadPtr.distanceTo(deqPtr) < ROB_SIZE.U && deqEntry(i).executed
  }

  commitValid := deqValid
  when(io.IN_flush || robStall) {
    commitValid := VecInit(Seq.fill(COMMIT_WIDTH)(false.B))
  }

  for (i <- 0 until COMMIT_WIDTH) {   
    commitUop(i).rd := deqEntry(i).rd
    commitUop(i).prd := deqEntry(i).prd
    commitUop(i).robPtr := robTailPtr + i.U
    flagUopNextValid := deqValid(i) && deqEntry(i).flag =/= FlagOp.NONE
    flagUopNext.target := deqEntry(i).target
    flagUopNext.flag := deqEntry(i).flag
    flagUopNext.rd := deqEntry(i).rd
    flagUopNext.pc := deqEntry(i).pc
    flagUopNext.robPtr := robTailPtr + i.U
    when(flagUopNextValid && deqEntry(i).flag =/= FlagOp.MISPREDICT) {
      commitUop(i).rd := ZERO
      commitUop(i).prd := ZERO
    }
  }

  robStall := !robStall && flagUopNextValid
  flagUop := flagUopNext

  flagUopValid := flagUopNextValid
  when(io.IN_flush || robStall) {
    flagUopValid := false.B
  }

  val loadCommited = (0 until COMMIT_WIDTH).map(i => deqValid(i) && deqEntry(i).isLoad)
  val storeCommited = (0 until COMMIT_WIDTH).map(i => deqValid(i) && deqEntry(i).isStore)

  when(io.IN_flush) {
    robHeadPtr := RingBufferPtr(size = ROB_SIZE, flag = 0.U, index = 0.U)
    robTailPtr := RingBufferPtr(size = ROB_SIZE, flag = 1.U, index = 0.U)
    ldqTailPtr := RingBufferPtr(size = LDQ_SIZE, flag = 1.U, index = 0.U)
    stqTailPtr := RingBufferPtr(size = STQ_SIZE, flag = 1.U, index = 0.U)
  }.elsewhen(!robStall) {
    when(!enqStall){
      robHeadPtr := io.IN_renameRobHeadPtr
    }
    robTailPtr := robTailPtr + PopCount(deqValid)
    ldqTailPtr := ldqTailPtr + PopCount(loadCommited)
    stqTailPtr := stqTailPtr + PopCount(storeCommited)
  }

  io.OUT_flagUop.valid := flagUopValid
  io.OUT_flagUop.bits := flagUop  

  // ** writeback
  for (i <- 0 until MACHINE_WIDTH) {
    val wbPtr = io.IN_writebackUop(i).bits.robPtr
    val wbEntry = rob(wbPtr.index)
    when (io.IN_writebackUop(i).valid && io.IN_writebackUop(i).bits.dest === Dest.ROB) {
      wbEntry.executed := true.B
      wbEntry.target := io.IN_writebackUop(i).bits.target
      wbEntry.flag := io.IN_writebackUop(i).bits.flag
    }
  }

  io.OUT_robTailPtr := robTailPtr
  for (i <- 0 until COMMIT_WIDTH) {
    io.OUT_commitUop(i).valid := commitValid(i)
    io.OUT_commitUop(i).bits := commitUop(i)
  }
}
