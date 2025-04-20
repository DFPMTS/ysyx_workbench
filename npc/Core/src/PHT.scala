import chisel3._
import chisel3.util._
import utils._

class SaturatedCounter extends CoreBundle {
  val counter = UInt(2.W)
  def nextState(taken: Bool) = {
    val nextCounter = Wire(new SaturatedCounter)
    nextCounter.counter := Mux(taken, 
      Mux(counter === 3.U, 3.U, counter + 1.U), 
      Mux(counter === 0.U, 0.U, counter - 1.U))
    nextCounter
  }
}

class PHTUpdate extends CoreBundle {
  val pc = UInt(XLEN.W)
  val taken = Bool()
}

class PHTIO extends CoreBundle {
  val IN_phtUpdate = Flipped(Valid(new PHTUpdate))
  val IN_pc = Flipped(UInt(XLEN.W))
  val OUT_phtTaken = Vec(FETCH_WIDTH, Bool())
}

class PHT extends CoreModule {
  val io = IO(new PHTIO)
  val pht = Seq.fill(FETCH_WIDTH)(RegInit(VecInit(Seq.fill(PHT_SIZE)("b10".U.asTypeOf(new SaturatedCounter)))))

  val phtIndex = io.IN_pc(PHT_INDEX_LEN - 1 + log2Up(FETCH_WIDTH * 4), log2Up(FETCH_WIDTH * 4))
  val phtUpdateBank = if (FETCH_WIDTH == 1) 0.U else io.IN_phtUpdate.bits.pc(log2Up(FETCH_WIDTH) - 1 + 2, 2)
  val phtUpdateIndex = io.IN_phtUpdate.bits.pc(PHT_INDEX_LEN - 1 + log2Up(FETCH_WIDTH * 4), log2Up(FETCH_WIDTH * 4))
  val phtUpdateTaken = io.IN_phtUpdate.bits.taken

  for (i <- 0 until FETCH_WIDTH) {
    io.OUT_phtTaken(i) := RegNext(pht(i)(phtIndex).counter(1))
  }

  for (i <- 0 until FETCH_WIDTH) {
    when(io.IN_phtUpdate.valid && phtUpdateBank === i.U) {
      pht(i)(phtUpdateIndex) := pht(i)(phtUpdateIndex).nextState(phtUpdateTaken)
    }
  }
}