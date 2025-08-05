import chisel3._
import chisel3.util._
import utils._
import FuType.BRU

class ROBIO extends CoreBundle {
  val IN_renameUop = Flipped(Vec(ISSUE_WIDTH, Valid(new RenameUop)))
  val IN_writebackUop = Flipped(Vec(WRITEBACK_WIDTH, Valid(new WritebackUop)))
  // val OUT_isROBWalk = Bool()
  val OUT_commitUop = Vec(COMMIT_WIDTH, Valid(new CommitUop))  
  val OUT_robTailPtr = RingBufferPtr(ROB_SIZE)
  val OUT_ldqTailPtr = RingBufferPtr(LDQ_SIZE)
  val OUT_stqTailPtr = RingBufferPtr(STQ_SIZE)
  val IN_stqBasePtr = Flipped(RingBufferPtr(STQ_SIZE))
  val IN_renameRobHeadPtr = Input(RingBufferPtr(ROB_SIZE))
  val OUT_flagUop = Valid(new FlagUop)
  val OUT_robEmpty = Bool()

  val IN_storeDataStqLimit = Flipped(Valid(RingBufferPtr(STQ_SIZE)))

  val OUT_phtUpdate = Valid(new PHTUpdate)
  val OUT_rasUpdate = Valid(new RASUpdate)

  val OUT_instCommited = Output(UInt(32.W))

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
  // !
  val ldqPtr = RingBufferPtr(LDQ_SIZE)
  val stqPtr = RingBufferPtr(STQ_SIZE)
  val result = UInt(XLEN.W)
  // !

  val isLoad = Bool()
  val isStore = Bool()

  val isCall = Bool()
  val isRet = Bool()

  val phtState = new SaturatedCounter
  val isLastBranch = Bool()
}

class ROB extends CoreModule with HasPerfCounters {
  val io = IO(new ROBIO)
  
  val instCommited = RegInit(0.U(32.W))
  io.OUT_instCommited := instCommited

  val rob = Reg(Vec(ROB_SIZE, new ROBEntry))
  val robStall = RegInit(false.B)

  // ** ROB head/tail
  val robHeadPtr = RegInit(RingBufferPtr(size = ROB_SIZE, flag = 0.U, index = 0.U))  
  val robTailPtr = RegInit(RingBufferPtr(size = ROB_SIZE, flag = 1.U, index = 0.U))

  // ** Ldq/Stq tail
  val ldqCommitPtr = RegInit(RingBufferPtr(size = LDQ_SIZE, flag = 0.U, index = 0.U))
  val stqCommitPtr = RegInit(RingBufferPtr(size = STQ_SIZE, flag = 0.U, index = 0.U))

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
    enqEntry.isCall := renameUop.fuType === FuType.BRU && renameUop.opcode === BRUOp.CALL
    enqEntry.isRet := renameUop.fuType === FuType.BRU && renameUop.opcode === BRUOp.RET
    enqEntry.phtState := renameUop.phtState
    enqEntry.isLastBranch := renameUop.lastBranch
    // !
    enqEntry.ldqPtr := renameUop.ldqPtr
    enqEntry.stqPtr := renameUop.stqPtr
    enqEntry.result := 0.U
    // !
    when (io.IN_renameUop(i).fire) {
      rob(io.IN_renameUop(i).bits.robPtr.index) := enqEntry
    }
  }

  // ** dequeue (Commit)
  val commitUop = Reg(Vec(COMMIT_WIDTH, new CommitUop))
  val commitValid = RegInit(VecInit(Seq.fill(COMMIT_WIDTH)(false.B)))

  val phtUpdateNext = Wire(new PHTUpdate)
  val phtUpdateValidNext = Wire(Bool())
  phtUpdateNext := DontCare
  phtUpdateValidNext := false.B
  val phtUpdate = Reg(new PHTUpdate)
  val phtUpdateValid = RegInit(false.B)
  phtUpdate := DontCare
  phtUpdateValid := false.B

  val rasUpdate = Reg(new RASUpdate)
  val rasUpdateValid = RegInit(false.B)
  val rasUpdateNext = Wire(new RASUpdate)
  val rasUpdateValidNext = Wire(Bool())
  rasUpdateNext := DontCare
  rasUpdateValidNext := false.B

  val flagUopNextValid = Wire(Bool())  
  val flagUopNext = Wire(new FlagUop)
  flagUopNext := DontCare
  flagUopNextValid := false.B

  val flagUopValid = RegInit(false.B)
  val flagUop     = Reg(new FlagUop)

  // * DeqEntry/Valid: Whether the entry is ready to commit
  val deqEntry = Wire(Vec(COMMIT_WIDTH, new ROBEntry))
  val deqValid = Wire(Vec(COMMIT_WIDTH, Bool()))
  val deqCanCommit = Wire(Vec(COMMIT_WIDTH, Bool()))
  // * Whether the entry has a flag need to handle
  val flagValid = Wire(Vec(COMMIT_WIDTH, Bool()))

  for (i <- 0 until COMMIT_WIDTH) {
    val deqPtr = robTailPtr + i.U
    val ptrYes = robHeadPtr.isLeq(deqPtr)
    dontTouch(ptrYes)
    deqEntry(i) := rob(deqPtr.index)
    deqValid(i) := robHeadPtr.isLeq(deqPtr) && deqEntry(i).executed
    flagValid(i) := deqEntry(i).flag =/= FlagOp.NONE || deqEntry(i).isCall || deqEntry(i).isRet
    when (io.IN_flush) {
      deqValid(i) := false.B
    }
  }

  deqCanCommit(0) := deqValid(0)
  for (i <- 1 until COMMIT_WIDTH) {
    deqCanCommit(i) := deqValid.take(i + 1).reduce(_ && _) && !flagValid.take(i).reduce(_ || _) 
  }

  commitValid := deqCanCommit
  when(io.IN_flush || robStall) {
    commitValid := VecInit(Seq.fill(COMMIT_WIDTH)(false.B))
  }

  for (i <- 0 until COMMIT_WIDTH) {   
    commitUop(i).rd := deqEntry(i).rd
    commitUop(i).prd := deqEntry(i).prd
    commitUop(i).robPtr := robTailPtr + i.U
    // !
    commitUop(i).pc := deqEntry(i).pc
    commitUop(i).target := deqEntry(i).target
    commitUop(i).flag := deqEntry(i).flag
    commitUop(i).ldqPtr := deqEntry(i).ldqPtr
    commitUop(i).stqPtr := deqEntry(i).stqPtr
    commitUop(i).result := deqEntry(i).result
    // !
    when(deqCanCommit(i) && flagValid(i)) {  
      flagUopNextValid := deqValid(i) && deqEntry(i).flag =/= FlagOp.NONE && !FlagOp.isNoRedirect(deqEntry(i).flag)
      flagUopNext.target := deqEntry(i).target
      flagUopNext.flag := deqEntry(i).flag
      flagUopNext.rd := deqEntry(i).rd
      flagUopNext.pc := deqEntry(i).pc
      flagUopNext.robPtr := robTailPtr + i.U
      flagUopNext.stqPtr := deqEntry(i).stqPtr

      val branchTaken = FlagOp.isBranchTaken(deqEntry(i).flag)
      val branchNotTaken = FlagOp.isBranchNotTaken(deqEntry(i).flag)
      phtUpdateValidNext := deqValid(i) && (branchTaken || branchNotTaken)
      phtUpdateNext.pc := deqEntry(i).pc
      phtUpdateNext.taken := branchTaken
      phtUpdateNext.nextState := deqEntry(i).phtState.nextState(branchTaken)
      phtUpdateNext.isLastBranch := deqEntry(i).isLastBranch

      rasUpdateValidNext := deqValid(i) && (deqEntry(i).isCall || deqEntry(i).isRet)
      rasUpdateNext.push := deqEntry(i).isCall
      rasUpdateNext.target := deqEntry(i).pc

      when(flagUopNextValid && !FlagOp.isBruFlags(deqEntry(i).flag)) {
        commitUop(i).rd := ZERO
        commitUop(i).prd := ZERO
      }
    }
  }

  instCommited := instCommited + PopCount(commitValid)

  robStall := !robStall && flagUopNextValid
  flagUop := flagUopNext
  phtUpdate := phtUpdateNext
  rasUpdate := rasUpdateNext

  flagUopValid := flagUopNextValid
  phtUpdateValid := phtUpdateValidNext
  rasUpdateValid := rasUpdateValidNext
  when(io.IN_flush || robStall) {
    flagUopValid := false.B
    phtUpdateValid := false.B
    rasUpdateValid := false.B
  }

  val loadCommited = (0 until COMMIT_WIDTH).map(i => deqCanCommit(i) && deqEntry(i).isLoad && deqEntry(i).flag === FlagOp.NONE)
  val storeCommited = (0 until COMMIT_WIDTH).map(i => deqCanCommit(i) && deqEntry(i).isStore && deqEntry(i).flag === FlagOp.NONE)

  when(io.IN_flush) {
    robHeadPtr := RingBufferPtr(size = ROB_SIZE, flag = 0.U, index = 0.U)
    robTailPtr := RingBufferPtr(size = ROB_SIZE, flag = 1.U, index = 0.U)
  }.elsewhen(!robStall) {
    robHeadPtr := robHeadPtr + PopCount(io.IN_renameUop.map(_.fire))
    robTailPtr := robTailPtr + PopCount(deqCanCommit)
    ldqCommitPtr := ldqCommitPtr + PopCount(loadCommited)
    stqCommitPtr := stqCommitPtr + PopCount(storeCommited)
  }

  io.OUT_flagUop.valid := flagUopValid
  io.OUT_flagUop.bits := flagUop  

  io.OUT_phtUpdate.valid := phtUpdateValid
  io.OUT_phtUpdate.bits := phtUpdate

  io.OUT_rasUpdate.valid := rasUpdateValid
  io.OUT_rasUpdate.bits := rasUpdate

  // ** writeback
  for (i <- 0 until WRITEBACK_WIDTH) {
    val wbPtr = io.IN_writebackUop(i).bits.robPtr
    val wbEntry = rob(wbPtr.index)
    when (io.IN_writebackUop(i).valid && io.IN_writebackUop(i).bits.dest === Dest.ROB) {
      wbEntry.executed := true.B
      wbEntry.target := io.IN_writebackUop(i).bits.target
      wbEntry.flag := io.IN_writebackUop(i).bits.flag
      wbEntry.result := io.IN_writebackUop(i).bits.data
    }
  }

  io.OUT_robEmpty := robHeadPtr.isEmpty(robTailPtr)
  io.OUT_robTailPtr := robTailPtr
  io.OUT_ldqTailPtr := ldqCommitPtr
  io.OUT_stqTailPtr := stqCommitPtr
  for (i <- 0 until COMMIT_WIDTH) {
    io.OUT_commitUop(i).valid := commitValid(i)
    io.OUT_commitUop(i).bits := commitUop(i)
  }

  // monitorEvent(totalBranch, deqValid(0) && (deqEntry(0).flag === FlagOp.BRANCH_TAKEN || 
  //              deqEntry(0).flag === FlagOp.BRANCH_NOT_TAKEN || deqEntry(0).flag === FlagOp.MISPREDICT_NOT_TAKEN || 
  //              deqEntry(0).flag === FlagOp.MISPREDICT_TAKEN))
  // monitorEvent(branchMisPred, deqValid(0) && (deqEntry(0).flag === FlagOp.MISPREDICT_TAKEN || 
  //              deqEntry(0).flag === FlagOp.MISPREDICT_NOT_TAKEN))
  monitorEvent(totalBranch, deqValid(0) && FlagOp.isBruFlags(deqEntry(0).flag))
  monitorEvent(branchMisPred, deqValid(0) && (deqEntry(0).flag === FlagOp.MISPREDICT_JUMP || 
              deqEntry(0).flag === FlagOp.MISPREDICT_TAKEN || deqEntry(0).flag === FlagOp.MISPREDICT_NOT_TAKEN))
}
