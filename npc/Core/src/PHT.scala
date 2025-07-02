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
  val nextState = new SaturatedCounter
}

class GHRUpdate extends CoreBundle {
  val taken = Bool()
}

class PHTIO extends CoreBundle {
  val IN_bpGHRUpdate = Flipped(Valid(new GHRUpdate))
  val IN_phtUpdate = Flipped(Valid(new PHTUpdate))
  val IN_pc = Flipped(UInt(XLEN.W))
  val OUT_phtState = Vec(FETCH_WIDTH, new SaturatedCounter)

  val IN_flush = Flipped(Bool())
}

class PHT extends CoreModule {
  val io = IO(new PHTIO)
  // val pht = Seq.fill(FETCH_WIDTH)(RegInit(VecInit(Seq.fill(PHT_SIZE)("b10".U.asTypeOf(new SaturatedCounter)))))
  val pht = Seq.fill(FETCH_WIDTH)(Module(new XilinxBRAM(PHT_SIZE, (new SaturatedCounter).getWidth, (new SaturatedCounter).getWidth)))

  val specGHR = RegInit(0.U(GHR_LEN.W))
  val specGHRNext = WireInit(specGHR)
  val archGHR = RegInit(0.U(GHR_LEN.W))
  val curArchHist = RegInit(false.B)

  // https://docs.boom-core.org/en/latest/sections/branch-prediction/backing-predictor.html#the-gshare-predictor
  def foldedHist(hist: UInt, l: Int) = {
    val nChunks = (GHR_LEN + l - 1) / l
    val hist_chunks = (0 until nChunks) map {i =>
      hist(scala.math.min((i+1)*l, GHR_LEN)-1, i*l)
    }
    hist_chunks.reduce(_^_)
  }

  val foldedSpecGHRNext = foldedHist(specGHRNext, FOLDED_GHR_LEN)
  val foldedArchGHR = foldedHist(archGHR, FOLDED_GHR_LEN)

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

  // val phtIndex = io.IN_pc(PHT_INDEX_LEN - 1 + log2Up(FETCH_WIDTH * 4), log2Up(FETCH_WIDTH * 4)) ^ (foldedSpecGHRNext << (PHT_INDEX_LEN - FOLDED_GHR_LEN))
  // upper GHR, lower PC
  val phtIndex= Cat(foldedSpecGHRNext, io.IN_pc(PHT_INDEX_LEN - FOLDED_GHR_LEN - 1 + log2Up(FETCH_WIDTH * 4), log2Up(FETCH_WIDTH * 4)))
  val phtUpdateBank = if (FETCH_WIDTH == 1) 0.U else io.IN_phtUpdate.bits.pc(log2Up(FETCH_WIDTH) - 1 + 2, 2)
  // val phtUpdateIndex = io.IN_phtUpdate.bits.pc(PHT_INDEX_LEN - 1 + log2Up(FETCH_WIDTH * 4), log2Up(FETCH_WIDTH * 4)) ^ (foldedArchGHR << (PHT_INDEX_LEN - FOLDED_GHR_LEN))
  val phtUpdateIndex = Cat(foldedArchGHR, io.IN_phtUpdate.bits.pc(PHT_INDEX_LEN - FOLDED_GHR_LEN - 1 + log2Up(FETCH_WIDTH * 4), log2Up(FETCH_WIDTH * 4)))
  val phtUpdateTaken = io.IN_phtUpdate.bits.taken

  for (i <- 0 until FETCH_WIDTH) {
    pht(i).io.r(phtIndex, 1, true.B)
    io.OUT_phtState(i) := pht(i).io.r.rdata.asTypeOf(new SaturatedCounter)
  }

  for (i <- 0 until FETCH_WIDTH) {
    pht(i).io.rw(phtUpdateIndex, 1, true.B, 0.U, 1.U, io.IN_phtUpdate.bits.nextState.asUInt, false.B)
    when(io.IN_phtUpdate.valid && phtUpdateBank === i.U) {
      pht(i).io.rw(phtUpdateIndex, 1, true.B, 0.U, 1.U, io.IN_phtUpdate.bits.nextState.asUInt, true.B)
    }
  }
}