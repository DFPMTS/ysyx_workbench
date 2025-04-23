import chisel3._
import chisel3.util._
import utils._

class FreeListIO extends CoreBundle {
  // * Allocate new PReg
  val IN_renameReqValid = Flipped(Vec(ISSUE_WIDTH, Bool()))
  val OUT_renamePReg = Vec(ISSUE_WIDTH, UInt(PREG_IDX_W))
  val OUT_renameStall = Bool()

  // * Free PReg (Commit)
  val IN_commitValid = Flipped(Vec(COMMIT_WIDTH, Bool()))
  val IN_commitRd = Flipped(Vec(COMMIT_WIDTH, UInt(5.W)))
  val IN_commitPReg = Flipped(Vec(COMMIT_WIDTH, UInt(PREG_IDX_W)))
  val IN_commitPrevPReg = Flipped(Vec(COMMIT_WIDTH, UInt(PREG_IDX_W)))

  // * Reset
  val IN_flush = Input(Bool())
}

class FreeList extends CoreModule {
  val io = IO(new FreeListIO)
  val freeList = RegInit(VecInit((1 until NUM_PREG).map(_.U(PREG_IDX_W))))

  // * Request consumes PRegs, so headPtr is after tailPtr
  val headPtr = RegInit(RingBufferPtr(size = NUM_PREG - 1, flag = 0.U, index = 0.U))
  val archHeadPtr = RegInit(RingBufferPtr(size = NUM_PREG - 1, flag = 0.U, index = 0.U))
  val tailPtr = RegInit(RingBufferPtr(size = NUM_PREG - 1, flag = 1.U, index = 0.U))
  
  
  // * Allocate new PReg
  for (i <- 0 until ISSUE_WIDTH) {
    val offset = PopCount(io.IN_renameReqValid.take(i))
    val allocateIndex = (headPtr + offset).index
    io.OUT_renamePReg(i) := freeList(allocateIndex)
  }
  // * Stall when not enough free PReg
  io.OUT_renameStall := headPtr.distanceTo(tailPtr) < ISSUE_WIDTH.U
  when(io.IN_flush) {
    headPtr := archHeadPtr
  }.elsewhen(!io.OUT_renameStall) {
    headPtr := headPtr + PopCount(io.IN_renameReqValid)
  }  
  

  // * Free PReg (Commit)
  // ** find the latest commit of the same rd
  val isCommitLatest = Wire(Vec(COMMIT_WIDTH, Bool()))
  for (i <- 0 until COMMIT_WIDTH) {    
    isCommitLatest(i) := true.B
    for (j <- i + 1 until COMMIT_WIDTH) {
      when(io.IN_commitValid(j) && io.IN_commitRd(i) === io.IN_commitRd(j)) {
        isCommitLatest(i) := false.B
      }
    }
  }
  // ** free PReg
  // ** the latest commit of the same rd will free the PrevPReg
  // ** the other commits will free the PReg (since their own PReg is killed by the latest)
  val freeValid = Wire(Vec(COMMIT_WIDTH, Bool()))
  val allocateConfirm = Wire(Vec(COMMIT_WIDTH, Bool()))
  for(i <- 0 until COMMIT_WIDTH) {
    freeValid(i) := false.B
    allocateConfirm(i) := false.B
    when(io.IN_commitValid(i)) {
      val commitPReg = io.IN_commitPReg(i)
      val commitPrevPReg = io.IN_commitPrevPReg(i)
      val offset = PopCount(freeValid.take(i))
      val commitIndex = (tailPtr + offset).index
      when (isCommitLatest(i)) {
        when (commitPrevPReg =/= 0.U) {
          freeList(commitIndex) := commitPrevPReg
          freeValid(i) := true.B
        }
      } .otherwise {        
        when (commitPReg =/= 0.U) {
          freeList(commitIndex) := commitPReg
          freeValid(i) := true.B
        }        
      }
      when(io.IN_commitRd(i) =/= 0.U) {
        allocateConfirm(i) := true.B
      }    
    }
  }

  tailPtr := tailPtr + PopCount(freeValid)
  archHeadPtr := archHeadPtr + PopCount(allocateConfirm)
}
