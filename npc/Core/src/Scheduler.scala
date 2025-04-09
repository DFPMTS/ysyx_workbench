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
    io.OUT_renameUop(i).bits := io.IN_renameUop(i)
  }

  // * Port 0: ALU/MUL/CSR
  // * Port 1: ALU/DIV
  // * Port 2: BRU
  // * Port 3: LSU
  def isPort(portIndex: Int, uop: RenameUop) = {
    if (portIndex == 0) {
      (uop.fuType === FuType.ALU || uop.fuType === FuType.MUL || uop.fuType === FuType.CSR) &&
      (!(uop.fuType === FuType.ALU) || uop.robPtr.index(0) === 0.U)
    }else if (portIndex == 1) {
      (uop.fuType === FuType.ALU || uop.fuType === FuType.DIV) && 
      (!(uop.fuType === FuType.ALU) || uop.robPtr.index(0) === 1.U)
    }else if (portIndex == 2) {
      uop.fuType === FuType.BRU
    }else {
      uop.fuType === FuType.LSU || uop.fuType === FuType.AMO
    }
  }

  val portVaild = Seq.fill(ISSUE_WIDTH)(Wire(Vec(MACHINE_WIDTH, Bool())))
  for (i <- 0 until ISSUE_WIDTH) {
    dontTouch(portVaild(i))
  }
  for (i <- 0 until ISSUE_WIDTH) {
    for (j <- 0 until MACHINE_WIDTH) {
      portVaild(i)(j) := isPort(j, io.IN_renameUop(i)) && io.OUT_renameUop(j).ready && io.IN_issueQueueValid(i)
    }
  }

  val portChoice = Seq.fill(ISSUE_WIDTH)(Wire(UInt(MACHINE_WIDTH.W)))
  val portBusy = Seq.fill(ISSUE_WIDTH)(WireInit(0.U(MACHINE_WIDTH.W)))
  val portAvail = Seq.fill(ISSUE_WIDTH)(Wire(UInt(MACHINE_WIDTH.W)))
  val hasPortAvail = Seq.fill(ISSUE_WIDTH)(Wire(Bool()))

  portAvail(0) := portVaild(0).asUInt
  hasPortAvail(0) := portAvail(0).orR
  portChoice(0) := VecInit(PriorityEncoderOH(portVaild(0))).asUInt
  portBusy(0) := portChoice(0)

  for (i <- 1 until ISSUE_WIDTH) {
    portAvail(i) := portVaild(i).asUInt & (~portBusy(i - 1))
    hasPortAvail(i) := portAvail(i).orR
    portChoice(i) := VecInit(PriorityEncoderOH(portAvail(i))).asUInt
    portBusy(i) := portBusy(i - 1) | portChoice(i)
  }  
  
  val portSrc = Seq.fill(MACHINE_WIDTH)(Wire(Vec(ISSUE_WIDTH, Bool())))
  for (i <- 0 until MACHINE_WIDTH) {
    for (j <- 0 until ISSUE_WIDTH) {
      portSrc(i)(j) := portChoice(j)(i)
    }
  }

  for(i <- 0 until MACHINE_WIDTH) {
    io.OUT_renameUop(i).bits := Mux1H(portSrc(i), io.IN_renameUop)
    io.OUT_renameUop(i).valid := portSrc(i).reduce(_ || _)    
  }
  for(i <- 0 until ISSUE_WIDTH) {
    io.OUT_issueQueueReady(i) := hasPortAvail(i)
  }
}