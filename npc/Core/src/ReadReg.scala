import chisel3._
import chisel3.util._
import utils._
import SrcType.IMM

class ReadRegIO extends CoreBundle {
  val IN_issueUop = Flipped(Vec(MACHINE_WIDTH, Decoupled(new RenameUop)))
  val OUT_readRegIndex = Vec(MACHINE_WIDTH, Vec(2, UInt(PREG_IDX_W)))
  val IN_readRegVal = Flipped(Vec(MACHINE_WIDTH, Vec(2, UInt(XLEN.W))))
  val OUT_readRegUop = Vec(MACHINE_WIDTH, Decoupled(new ReadRegUop))
  val IN_flush = Input(Bool())
}

class ReadReg extends CoreModule {
    val io = IO(new ReadRegIO)

    // * Main signals: readRegUop
    val uop = Reg(Vec(MACHINE_WIDTH, new ReadRegUop))
    val uopNext = Wire(Vec(MACHINE_WIDTH, new ReadRegUop))
    val uopValid = RegInit(VecInit(Seq.fill(MACHINE_WIDTH)(false.B)))

    for (i <- 0 until MACHINE_WIDTH) {
      
    }
    
    for (i <- 0 until MACHINE_WIDTH) {
      val issueUop = io.IN_issueUop(i).bits

      uopNext(i).rd := issueUop.rd
      uopNext(i).prd := issueUop.prd

      uopNext(i).prs1 := issueUop.prs1
      uopNext(i).prs2 := issueUop.prs2

      io.OUT_readRegIndex(i)(0) := issueUop.prs1
      io.OUT_readRegIndex(i)(1) := issueUop.prs2
      uopNext(i).src1 := Mux(issueUop.src1Type === SrcType.PC, issueUop.pc, io.IN_readRegVal(i)(0))
      uopNext(i).src2 := Mux(issueUop.src2Type === SrcType.IMM, issueUop.imm, io.IN_readRegVal(i)(1))

      uopNext(i).robPtr := issueUop.robPtr
      uopNext(i).ldqPtr := issueUop.ldqPtr
      uopNext(i).stqPtr := issueUop.stqPtr

      uopNext(i).pc := issueUop.pc
      uopNext(i).imm := issueUop.imm

      uopNext(i).fuType := issueUop.fuType
      uopNext(i).opcode := issueUop.opcode

      uopNext(i).predTarget := issueUop.predTarget
      uopNext(i).compressed := issueUop.compressed
    }

    // * Control
    val outReady = io.OUT_readRegUop.map(_.ready)
    for (i <- 0 until MACHINE_WIDTH) {
      val inReady = !uopValid(i) || outReady(i)
      io.IN_issueUop(i).ready := inReady
      when (inReady) {
        uop(i) := uopNext(i)
        uopValid(i) := io.IN_issueUop(i).valid
      }
    }

    when(io.IN_flush) {
      uopValid := VecInit(Seq.fill(MACHINE_WIDTH)(false.B))
    }

    // ** Output
    for (i <- 0 until MACHINE_WIDTH) {
      io.OUT_readRegUop(i).valid := uopValid(i)
      io.OUT_readRegUop(i).bits := uop(i)
    }
}

