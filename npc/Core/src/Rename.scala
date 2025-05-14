import chisel3._
import chisel3.util._
import utils._
import chisel3.SpecifiedDirection.Flip

class RenameIO extends CoreBundle {
  // * rename
  val IN_decodeUop = Flipped(Vec(ISSUE_WIDTH, Decoupled(new DecodeUop)))
  val OUT_renameUop = Vec(ISSUE_WIDTH, new RenameUop)
  
  val OUT_robValid = Vec(ISSUE_WIDTH, Output(Bool()))
  val IN_robTailPtr = Input(RingBufferPtr(ROB_SIZE))
  val IN_robEmpty = Flipped(Bool())
  // * ldq/stq tail
  val IN_ldqTailPtr = Input(RingBufferPtr(LDQ_SIZE))
  val IN_stqTailPtr = Input(RingBufferPtr(STQ_SIZE))

  val OUT_issueQueueValid = Vec(ISSUE_WIDTH, Output(Bool()))  
  val IN_issueQueueReady = Flipped(Vec(ISSUE_WIDTH, Bool()))
  // * writeback
  val IN_writebackUop = Flipped(Vec(WRITEBACK_WIDTH, Valid(new WritebackUop)))
  // * commit
  val IN_commitUop = Flipped(Vec(COMMIT_WIDTH, Valid(new CommitUop)))
  val OUT_robHeadPtr = Output(RingBufferPtr(ROB_SIZE))
  val IN_storeQueueEmpty = Flipped(Bool())
  val IN_storeBufferEmpty = Flipped(Bool())

  val IN_flush     = Input(Bool())
}

class Rename extends CoreModule {
  val io = IO(new RenameIO)

  // * Main signals: renameUop
  val uopNext = Wire(Vec(ISSUE_WIDTH, new RenameUop))

  // * Submodules
  val renamingTable = Module(new RenamingTable)
  val freeList = Module(new FreeList)
  val renameBuffer = Module(new RenameBuffer)

  // * Dataflow

  // ** robPtr/ldqPtr/stqPtr allocation
  val robHeadPtr = RegInit(RingBufferPtr(size = ROB_SIZE, flag = 0.U, index = 0.U))
  val ldqHeadPtr = RegInit(RingBufferPtr(size = LDQ_SIZE, flag = 0.U, index = 0.U))
  val stqHeadPtr = RegInit(RingBufferPtr(size = STQ_SIZE, flag = 0.U, index = 0.U))

  val ldqInc = io.IN_decodeUop.map(uop => uop.fire && uop.bits.fuType === FuType.LSU && LSUOp.isLoad(uop.bits.opcode))
  val stqInc = io.IN_decodeUop.map(uop => uop.fire && uop.bits.fuType === FuType.LSU && LSUOp.isStore(uop.bits.opcode))

  val ldqIncPrefixSum = ldqInc.scanLeft(0.U)(_ +& _)
  val stqIncPrefixSum = stqInc.scanLeft(0.U)(_ +& _)

  val ldqPtr = VecInit(ldqIncPrefixSum.map(psum => ldqHeadPtr + psum))
  val stqPtr = VecInit(stqIncPrefixSum.map(psum => stqHeadPtr + psum))

  when (io.IN_flush) {
    robHeadPtr := RingBufferPtr(size = ROB_SIZE, flag = 0.U, index = 0.U)
    ldqHeadPtr.flag := io.IN_ldqTailPtr.flag
    ldqHeadPtr.index := io.IN_ldqTailPtr.index
    stqHeadPtr := io.IN_stqTailPtr
  }.otherwise {
    val inFiredCnt = PopCount(io.IN_decodeUop.map(_.fire))
    robHeadPtr := robHeadPtr + inFiredCnt
    ldqHeadPtr := ldqPtr(inFiredCnt)
    stqHeadPtr := stqPtr(inFiredCnt)
  }
  io.OUT_robHeadPtr := robHeadPtr

  // ** Decode -> FreeList
  val allocatePReg = io.IN_decodeUop.map(decodeUop => decodeUop.fire && 
                                         decodeUop.bits.fuType =/= FuType.FLAG && 
                                         decodeUop.bits.rd =/= 0.U)
  val renameStall = freeList.io.OUT_renameStall || RegNext(robHeadPtr.distanceTo(io.IN_robTailPtr) < 2.U * ISSUE_WIDTH.U)
  dontTouch(renameStall)
  for (i <- 0 until ISSUE_WIDTH) {
    // * Allocate PReg
    freeList.io.IN_renameReqValid(i) := allocatePReg(i)
  }  

  // ** FreeList <- Commit
  for (i <- 0 until COMMIT_WIDTH) {
    freeList.io.IN_commitValid(i) := io.IN_commitUop(i).valid
    freeList.io.IN_commitRd(i) := io.IN_commitUop(i).bits.rd
    freeList.io.IN_commitPReg(i) := io.IN_commitUop(i).bits.prd
    freeList.io.IN_commitPrevPReg(i) := renamingTable.io.OUT_commitPrevPReg(i)
  }

  // ** Decode -> RenamingTable
  for (i <- 0 until ISSUE_WIDTH) {
    // * Read
    renamingTable.io.IN_renameReadAReg(i) := VecInit(io.IN_decodeUop(i).bits.rs1, io.IN_decodeUop(i).bits.rs2)
    // * Write
    renamingTable.io.IN_renameWriteValid(i) := allocatePReg(i)
    renamingTable.io.IN_renameWriteAReg(i) := io.IN_decodeUop(i).bits.rd
    renamingTable.io.IN_renameWritePReg(i) := freeList.io.OUT_renamePReg(i)
  }

  // ** RenamingTable <- Writeback
  for (i <- 0 until WRITEBACK_WIDTH) {
    renamingTable.io.IN_writebackValid(i) := io.IN_writebackUop(i).valid
    renamingTable.io.IN_writebackPReg(i) := io.IN_writebackUop(i).bits.prd
  }

  // ** RenamingTable <- Commit
  for (i <- 0 until COMMIT_WIDTH) {
    renamingTable.io.IN_commitValid(i) := io.IN_commitUop(i).valid
    renamingTable.io.IN_commitAReg(i) := io.IN_commitUop(i).bits.rd
    renamingTable.io.IN_commitPReg(i) := io.IN_commitUop(i).bits.prd
  }

  // ** uopNext generation
  for (i <- 0 until ISSUE_WIDTH) {
    val decodeUop = io.IN_decodeUop(i).bits

    uopNext(i).rd := decodeUop.rd
    uopNext(i).prd := Mux(allocatePReg(i), freeList.io.OUT_renamePReg(i), 0.U)
    uopNext(i).prs1 := renamingTable.io.OUT_renameReadPReg(i)(0)
    uopNext(i).prs2 := renamingTable.io.OUT_renameReadPReg(i)(1)

    uopNext(i).src1Type := decodeUop.src1Type
    uopNext(i).src2Type := decodeUop.src2Type

    uopNext(i).src1Ready := renamingTable.io.OUT_renameReadReady(i)(0)
    uopNext(i).src2Ready := renamingTable.io.OUT_renameReadReady(i)(1)

    uopNext(i).fuType := decodeUop.fuType
    uopNext(i).opcode := decodeUop.opcode

    uopNext(i).imm := decodeUop.imm
    uopNext(i).pc := decodeUop.pc    

    uopNext(i).predTarget := decodeUop.predTarget
    uopNext(i).compressed := decodeUop.compressed

    uopNext(i).lockBackend := decodeUop.lockBackend

    uopNext(i).phtState := decodeUop.phtState
    uopNext(i).lastBranch := decodeUop.lastBranch

    uopNext(i).robPtr := robHeadPtr + i.U
    uopNext(i).ldqPtr := ldqPtr(i)
    uopNext(i).stqPtr := stqPtr(i)

    uopNext(i).inst := decodeUop.inst
    uopNext(i).rs1 := decodeUop.rs1
    uopNext(i).rs2 := decodeUop.rs2
  }

  // * Control
  val inValid = io.IN_decodeUop.map(_.valid)
  val inFire = io.IN_decodeUop.map(_.fire)
  
  val inReady =  !renameStall && renameBuffer.io.OUT_bufferReady
  
  val needIQ = VecInit((0 until ISSUE_WIDTH).map(i => 
    inValid(i) && uopNext(i).fuType =/= FuType.FLAG))

  renameBuffer.io.IN_renameUop := uopNext
  renameBuffer.io.IN_issueQueueValid := needIQ
  renameBuffer.io.IN_robValid := inFire


  renameBuffer.io.IN_issueQueueReady := io.IN_issueQueueReady
  renameBuffer.io.IN_robEmpty := io.IN_robEmpty
  renameBuffer.io.IN_storeQueueEmpty := io.IN_storeQueueEmpty
  renameBuffer.io.IN_storeBufferEmpty := io.IN_storeBufferEmpty
  renameBuffer.io.IN_writebackUop := io.IN_writebackUop
  


  // ** Flush submodules
  renamingTable.io.IN_flush := io.IN_flush
  freeList.io.IN_flush := io.IN_flush
  renameBuffer.io.IN_flush := io.IN_flush

  // * Output
  io.OUT_issueQueueValid := renameBuffer.io.OUT_issueQueueValid
  io.OUT_robValid := renameBuffer.io.OUT_robValid
  io.OUT_renameUop := renameBuffer.io.OUT_renameUop

  // ** Decode <- Rename
  for (i <- 0 until ISSUE_WIDTH) {
    io.IN_decodeUop(i).ready := inReady
  }
}

class RenameBufferIO extends CoreBundle {
  val IN_renameUop = Flipped(Vec(ISSUE_WIDTH, (new RenameUop)))
  val IN_issueQueueValid = Flipped(Vec(ISSUE_WIDTH, Bool()))
  val IN_robValid = Flipped(Vec(ISSUE_WIDTH, Bool()))
  val OUT_bufferReady = Bool()

  val IN_writebackUop = Flipped(Vec(WRITEBACK_WIDTH, Valid(new WritebackUop)))

  val OUT_renameUop = Vec(ISSUE_WIDTH, new RenameUop)
  val OUT_issueQueueValid = Vec(ISSUE_WIDTH, Output(Bool()))  
  val IN_issueQueueReady = Flipped(Vec(ISSUE_WIDTH, Bool()))
  val OUT_robValid = Vec(ISSUE_WIDTH, Output(Bool()))
  
  val IN_robEmpty = Flipped(Bool())
  val IN_storeQueueEmpty = Flipped(Bool())
  val IN_storeBufferEmpty = Flipped(Bool())
  
  val IN_flush = Input(Bool())
}

class RenameBuffer extends CoreModule {
  val io = IO(new RenameBufferIO)

  val headPtr = RegInit(0.U(2.W))
  val tailPtr = RegInit(0.U(2.W))
  val bufferFull = headPtr(0) === tailPtr(0) && headPtr(1) =/= tailPtr(1)
  val bufferEmpty = headPtr === tailPtr
  val renamedUop = Reg(Vec(2, Vec(ISSUE_WIDTH, new RenameUop)))
  val renamedUopRobValid = RegInit(VecInit(Seq.fill(2)(VecInit(Seq.fill(ISSUE_WIDTH)(false.B)))))
  val renamedUopIQValid = RegInit(VecInit(Seq.fill(2)(VecInit(Seq.fill(ISSUE_WIDTH)(false.B)))))

  val backendLocked = RegInit(false.B)

  io.OUT_bufferReady := !bufferFull

  val uop = renamedUop(tailPtr(0))
  val uopRobValid = renamedUopRobValid(tailPtr(0))
  val uopIQValid = renamedUopIQValid(tailPtr(0))
  val uopValid = VecInit((0 until ISSUE_WIDTH).map(i => uopRobValid(i) || uopIQValid(i)))

  val backendEmpty = io.IN_robEmpty && io.IN_storeQueueEmpty && io.IN_storeBufferEmpty
  val isValidLockInst = VecInit((0 until ISSUE_WIDTH).map(i => {
    uopValid(i) && uop(i).lockBackend
  }))

  val isBlocked = Wire(Vec(ISSUE_WIDTH, Bool()))
  dontTouch(isBlocked)
  isBlocked(0) := isValidLockInst(0) && !backendEmpty
  // * IN_backendLocked: blocks all inst uncoditionally
  // * Logic: inst(0) blocked: self lock backend && rob not empty
  // *        inst(i) blocked: inst(i-1) blocked || (inst(i) lock backend && (rob not empty or inst before not issued))
  for (i <- 1 until ISSUE_WIDTH) {
    isBlocked(i) := isBlocked(i - 1) || ((uopValid.asUInt(i - 1, 0).orR || !backendEmpty) && isValidLockInst(i)) || 
                    isValidLockInst.asUInt(i - 1, 0).orR
  }

  // ** maintain current uop
  for (k <- 0 until 2) {
    val uop = renamedUop(k)
    for (i <- 0 until WRITEBACK_WIDTH) {
      when (io.IN_writebackUop(i).valid && io.IN_writebackUop(i).bits.prd =/= ZERO) {
        for (j <- 0 until ISSUE_WIDTH) {
          when (uop(j).prs1 === io.IN_writebackUop(i).bits.prd) {
            uop(j).src1Ready := true.B
          }
          when (uop(j).prs2 === io.IN_writebackUop(i).bits.prd) {
            uop(j).src2Ready := true.B
          }
        }
      }
    }
  }

  when(io.IN_robValid.head && !bufferFull) {
    for (i <- 0 until ISSUE_WIDTH) {
      renamedUop(headPtr(0))(i) := io.IN_renameUop(i)
      renamedUopRobValid(headPtr(0))(i) := io.IN_robValid(i)
      renamedUopIQValid(headPtr(0))(i) := io.IN_issueQueueValid(i)
    }
  }
  
  val lockInstIssued = (0 until ISSUE_WIDTH).map(i => {
    io.OUT_renameUop(i).lockBackend && Mux(io.OUT_renameUop(i).fuType === FuType.FLAG, 
      io.OUT_robValid(i), 
      io.OUT_issueQueueValid(i) && io.IN_issueQueueReady(i))
  }).reduce(_ || _)

  when(lockInstIssued) {
    backendLocked := true.B
  }.elsewhen (backendEmpty) {
    backendLocked := false.B
  }

  // ** Rename -> Issue
  for (i <- 0 until ISSUE_WIDTH) {
    io.OUT_robValid(i) := uopRobValid(i) && !backendLocked && !isBlocked(i)
    io.OUT_issueQueueValid(i) := uopIQValid(i) && !backendLocked && !isBlocked(i)
    io.OUT_renameUop(i) := uop(i)
  }

  val outIssueQueueFire = (0 until ISSUE_WIDTH).map(i => io.OUT_issueQueueValid(i) && io.IN_issueQueueReady(i))
  val inReady = (0 until ISSUE_WIDTH).map { i =>
    !uopValid(i) || outIssueQueueFire(i)
  }.reduce(_ && _)

  when (io.IN_flush) {
    headPtr := 0.U
    tailPtr := 0.U
    for (i <- 0 until ISSUE_WIDTH) {
      renamedUopRobValid(0)(i) := false.B
      renamedUopIQValid(0)(i) := false.B
      renamedUopRobValid(1)(i) := false.B
      renamedUopIQValid(1)(i) := false.B
    }
  }.otherwise {  
    for (i <- 0 until ISSUE_WIDTH) {
      when(outIssueQueueFire(i)) {
        uopIQValid(i) := false.B
      }
      when(io.OUT_robValid(i)) {
        uopRobValid(i) := false.B
      }
    }
    when (io.IN_robValid.head && !bufferFull) {
      headPtr := headPtr + 1.U
    }
    when(!bufferEmpty && inReady) {
      tailPtr := tailPtr + 1.U
    }
  }
}