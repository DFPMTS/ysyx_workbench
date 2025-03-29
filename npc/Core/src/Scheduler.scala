import chisel3._
import chisel3.util._
import utils._

class SchedulerIO extends CoreBundle {
  val IN_renameUop = Flipped(Vec(ISSUE_WIDTH, new RenameUop))
  val IN_issueQueueValid = Flipped(Vec(ISSUE_WIDTH, Bool()))
  val OUT_issueQueueReady = Vec(ISSUE_WIDTH, Bool())  
  val OUT_renameUop = Vec(MACHINE_WIDTH, Decoupled(new RenameUop))
}

class Scheduler extends CoreModule {
  val io = IO(new SchedulerIO)

  // * If the uop's Fu is not in any port, ready will be true
  for (i <- 0 until ISSUE_WIDTH) {
    io.OUT_issueQueueReady(i) := true.B
  }

  for (i <- 0 until MACHINE_WIDTH) {
    io.OUT_renameUop(i).valid := false.B
    io.OUT_renameUop(i).bits := io.IN_renameUop(0)
  }

  // * Port 0: ALU/BRU/MUL/CSR
  // * Port 1: ALU/BRU/DIV
  // * Port 2: LSU
  def isPort(portIndex: Int, uop: RenameUop) = {
    if (portIndex == 0) {
      (uop.fuType === FuType.ALU || uop.fuType === FuType.BRU || uop.fuType === FuType.MUL ||uop.fuType === FuType.CSR) &&
      (!(uop.fuType === FuType.ALU || uop.fuType === FuType.BRU) || uop.robPtr.index(0) === 0.U)
    }else if (portIndex == 1) {
      (uop.fuType === FuType.ALU || uop.fuType === FuType.BRU || uop.fuType === FuType.DIV) && 
      // (uop.fuType === FuType.DIV)  &&
      (!(uop.fuType === FuType.ALU || uop.fuType === FuType.BRU) || uop.robPtr.index(0) === 1.U)
    }else if (portIndex == 2) {
      uop.fuType === FuType.LSU || uop.fuType === FuType.AMO
    }else {
      false.B
    }
  }

  val portValid = (0 until MACHINE_WIDTH) map { portIndex =>
    (0 until ISSUE_WIDTH) map { i =>
      io.IN_issueQueueValid(i) && isPort(portIndex, io.IN_renameUop(i))
    }
  }

  val portValidIndex = portValid map { valid =>
    PriorityEncoder(valid)
  }

  val hasPortValid = portValid map { valid =>
    valid.reduce(_ || _)
  }

  for (i <- 0 until MACHINE_WIDTH) {
    when (hasPortValid(i)) {
      io.OUT_issueQueueReady(portValidIndex(i)) := io.OUT_renameUop(i).ready
      io.OUT_renameUop(i).valid := io.IN_issueQueueValid(portValidIndex(i))
    }
    io.OUT_renameUop(i).bits := io.IN_renameUop(portValidIndex(i))
  }
}