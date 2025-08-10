import chisel3._
import chisel3.util._
import utils._

class WritebackSelIO(N: Int) extends CoreBundle {
  val IN_uop = Flipped(Vec(N, Valid(new WritebackUop)))
  val OUT_uop = Valid(new WritebackUop)
}

class WritebackSel(N: Int) extends CoreModule {
  val io = IO(new WritebackSelIO(N))

  val validVec = io.IN_uop.map(_.valid)

  io.OUT_uop := Mux1H(validVec, io.IN_uop)
}
