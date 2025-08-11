import chisel3._
import chisel3.util._
import utils._

class PRegIO extends CoreBundle {
  val IN_pRegIndex = Flipped(Vec(MACHINE_WIDTH, Vec(2, UInt(PREG_IDX_W))))
  val IN_writebackUop = Flipped(Vec(WRITEBACK_WIDTH, Valid(new WritebackUop)))
  val OUT_pRegVal = Vec(MACHINE_WIDTH, Vec(2, UInt(XLEN.W)))
}

class PReg extends CoreModule {
  val io = IO(new PRegIO)

  val pReg = Reg(Vec(NUM_PREG, UInt(XLEN.W)))

  for (i <- 0 until MACHINE_WIDTH) {
    for (j <- 0 until 2) {
      when (io.IN_pRegIndex(i)(j) =/= ZERO) {
        // * bypass from current cycle's writeback
        val wbMatchVec = io.IN_writebackUop.map { writebackUop =>
          writebackUop.valid && writebackUop.bits.prd =/= ZERO && writebackUop.bits.prd === io.IN_pRegIndex(i)(j)
        }
        val wbCanForward = wbMatchVec.reduce(_ || _)
        val wbData = Mux1H(wbMatchVec, io.IN_writebackUop.map(_.bits.data))
        when (wbCanForward) {
          io.OUT_pRegVal(i)(j) := wbData
        }.otherwise {
          // * read PReg from pReg
          io.OUT_pRegVal(i)(j) := pReg(io.IN_pRegIndex(i)(j))
        }
      }.otherwise {
        io.OUT_pRegVal(i)(j) := ZERO
      }
    }
  }

  for (i <- 0 until MACHINE_WIDTH) {
    val writebackValid = io.IN_writebackUop(i).valid
    val writebackUop = io.IN_writebackUop(i).bits
    when (writebackValid && writebackUop.prd =/= ZERO) {
      pReg(writebackUop.prd) := writebackUop.data
    }
  }
}