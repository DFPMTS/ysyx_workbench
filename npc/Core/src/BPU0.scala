import chisel3._
import chisel3.util._
import utils._

class Prediction extends CoreBundle {
  val phtTaken = Vec(FETCH_WIDTH, Bool())
  // * Branch Taken?
  val brTaken = Bool()
  val brOffset = UInt(log2Up(FETCH_WIDTH).W)
  // * Branch Target 
  val btbValid  = Bool()
  val btbType   = BrType()
  val btbTarget = UInt(XLEN.W)
}

class BPU0IO extends CoreBundle {
  val IN_pcNext = Flipped(UInt(XLEN.W))
  val IN_pc     = Flipped(UInt(XLEN.W))
  
  val IN_btbUpdate = Flipped(Valid(new BTBUpdate))
  val IN_fixBTBUpdate = Flipped(Valid(new BTBUpdate))
  val IN_phtUpdate = Flipped(Valid(new PHTUpdate))

  val IN_rasBpUpdate = Flipped(Valid(new RASUpdate))
  val IN_rasCommitUpdate = Flipped(Valid(new RASUpdate))

  val OUT_prediction = new Prediction

  val IN_flush = Flipped(Bool())
}

class BPU0 extends CoreModule {
  val io = IO(new BPU0IO)
  
  val btb = Module(new BTB)
  val pht = Module(new PHT)
  val ras = Module(new RAS)

  btb.io.IN_pc := io.IN_pcNext
  btb.io.IN_btbUpdate := io.IN_btbUpdate
  btb.io.IN_fixBTBUpdate := io.IN_fixBTBUpdate

  pht.io.IN_pc := io.IN_pcNext
  pht.io.IN_phtUpdate := io.IN_phtUpdate

  ras.io.IN_bpUpdate := io.IN_rasBpUpdate
  ras.io.IN_commitUpdate := io.IN_rasCommitUpdate
  ras.io.IN_flush := io.IN_flush

  val fetchOffset = if (FETCH_WIDTH == 1) 0.U else io.IN_pc(log2Ceil(FETCH_WIDTH * 4) - 1, 2)
  val instValidMap = (Fill(FETCH_WIDTH, 1.U(1.W)) << fetchOffset)(FETCH_WIDTH - 1, 0)

  val btbValid = Wire(Vec(FETCH_WIDTH, Bool()))
  val btbTarget = Wire(Vec(FETCH_WIDTH, UInt(XLEN.W)))
  val btbBrType = Wire(Vec(FETCH_WIDTH, BrType()))
  for (i <- 0 until FETCH_WIDTH) {
    btbValid(i) := btb.io.OUT_btbRead(i).valid && btb.io.OUT_btbRead(i).tag === io.IN_pc(XLEN - 1, XLEN - BTB_TAG)
    btbTarget(i) := btb.io.OUT_btbRead(i).target
    btbBrType(i) := btb.io.OUT_btbRead(i).brType
  }

  val phtTaken = Wire(Vec(FETCH_WIDTH, Bool()))
  for (i <- 0 until FETCH_WIDTH) {
    phtTaken(i) := pht.io.OUT_phtTaken(i)
  }

  val brTaken = Wire(Vec(FETCH_WIDTH, Bool()))
  for (i <- 0 until FETCH_WIDTH) {
    brTaken(i) := btbValid(i) && (!(btbBrType(i) === BrType.BRANCH) || phtTaken(i))
  }

  // * Collect Predicted Branches
  val branchMap = Wire(Vec(FETCH_WIDTH, Bool()))
  dontTouch(branchMap)
  for (i <- 0 until FETCH_WIDTH) {
    branchMap(i) := instValidMap(i) && brTaken(i)
  }
  val brOffset = PriorityEncoder(branchMap)

  io.OUT_prediction.brOffset := brOffset
  io.OUT_prediction.phtTaken := phtTaken
  io.OUT_prediction.brTaken := branchMap.asUInt.orR
  io.OUT_prediction.btbValid := btbValid(brOffset)
  io.OUT_prediction.btbType := btbBrType(brOffset)
  io.OUT_prediction.btbTarget := Mux(btbBrType(brOffset) === BrType.RET, ras.io.OUT_top + 4.U, btbTarget(brOffset))
}
