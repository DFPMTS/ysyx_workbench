import chisel3._
import chisel3.util._
import utils._

class InstAlignerIO extends CoreBundle {
  val IN_fetchGroup = Flipped(Decoupled(new FetchGroup))

  val OUT_insts = Vec(FETCH_WIDTH, Valid(new InstSignal))
  val IN_ready = Flipped(Bool())

  val IN_flush = Flipped(Bool())
}

class InstAligner extends CoreModule {
  val io = IO(new InstAlignerIO)
  
  val inFetchGroup = io.IN_fetchGroup.bits
  val insts = Reg(Vec(FETCH_WIDTH, new InstSignal))
  val valid = RegInit(VecInit(Seq.fill(FETCH_WIDTH)(false.B)))

  val outReady = !valid(0) || io.IN_ready
  io.IN_fetchGroup.ready := outReady

  val fetchOffset = if (FETCH_WIDTH == 1) 0.U else inFetchGroup.pc(log2Ceil(FETCH_WIDTH * 4) - 1, 2)
  val instValidMap = (Fill(FETCH_WIDTH, 1.U(1.W)) << fetchOffset)(FETCH_WIDTH - 1, 0) & Fill(FETCH_WIDTH, io.IN_fetchGroup.valid)

  when(io.IN_flush) {
    valid := VecInit(Seq.fill(FETCH_WIDTH)(false.B))
  }.elsewhen(outReady) {
    for (i <- 0 until FETCH_WIDTH) {
      valid(i) := instValidMap(fetchOffset + i.U)
      insts(i).inst := inFetchGroup.insts(fetchOffset + i.U)
      val instPC = if (FETCH_WIDTH == 1) inFetchGroup.pc else Cat(inFetchGroup.pc(XLEN - 1, log2Ceil(FETCH_WIDTH * 4)), (fetchOffset + i.U), 0.U(2.W))
      insts(i).pc := instPC
      insts(i).pageFault := inFetchGroup.pageFault
      insts(i).interrupt := inFetchGroup.interrupt
      insts(i).access_fault := inFetchGroup.access_fault
    }
  }
  
  io.OUT_insts.zipWithIndex.foreach { case (out, i) =>
    out.valid := valid(i)
    out.bits := insts(i)
  }
}
