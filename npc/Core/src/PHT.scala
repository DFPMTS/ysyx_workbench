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
  val isLastBranch = Bool()
  val taken = Bool()
}

class GHRUpdate extends CoreBundle {
  val taken = Bool()
}

class PHTIO extends CoreBundle {
  val IN_bpGHRUpdate = Flipped(Valid(new GHRUpdate))
  val IN_phtUpdate = Flipped(Valid(new PHTUpdate))
  val IN_pc = Flipped(UInt(XLEN.W))
  val OUT_phtTaken = Vec(FETCH_WIDTH, Bool())

  val IN_flush = Flipped(Bool())
}

class PHT extends CoreModule {
  val io = IO(new PHTIO)
  val pht = Seq.fill(FETCH_WIDTH)(RegInit(VecInit(Seq.fill(PHT_SIZE)("b10".U.asTypeOf(new SaturatedCounter)))))

  val specGHR = RegInit(0.U(GHR_LEN.W))
  val specGHRNext = WireInit(specGHR)
  val archGHR = RegInit(0.U(GHR_LEN.W))
  val curArchHist = RegInit(false.B)

  dontTouch(specGHR)
  dontTouch(archGHR)
  dontTouch(curArchHist)

  when(io.IN_flush) {
    specGHRNext := archGHR
  }.elsewhen(io.IN_bpGHRUpdate.valid) {
    specGHRNext := Cat(specGHR(GHR_LEN - 2, 0), io.IN_bpGHRUpdate.bits.taken)
  }

  specGHR := specGHRNext

  when(io.IN_phtUpdate.valid) {
    val curArchHistNext = curArchHist || io.IN_phtUpdate.bits.taken
    curArchHist := curArchHistNext
    when(io.IN_phtUpdate.bits.isLastBranch) {
      curArchHist := false.B
      archGHR := Cat(archGHR(GHR_LEN - 2, 0), curArchHistNext)
    }
  }

  val phtIndex = io.IN_pc(PHT_INDEX_LEN - 1 + log2Up(FETCH_WIDTH * 4), log2Up(FETCH_WIDTH * 4)) ^ (specGHRNext << (PHT_INDEX_LEN - GHR_LEN))
  val phtUpdateBank = if (FETCH_WIDTH == 1) 0.U else io.IN_phtUpdate.bits.pc(log2Up(FETCH_WIDTH) - 1 + 2, 2)
  val phtUpdateIndex = io.IN_phtUpdate.bits.pc(PHT_INDEX_LEN - 1 + log2Up(FETCH_WIDTH * 4), log2Up(FETCH_WIDTH * 4)) ^ (archGHR << (PHT_INDEX_LEN - GHR_LEN))
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