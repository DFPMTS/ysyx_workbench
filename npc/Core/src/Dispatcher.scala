import chisel3._
import chisel3.util._
import utils._

class DispatcherIO(FUs: Seq[Seq[UInt]]) extends CoreBundle {
  val IN_uop = Flipped(Decoupled(new ReadRegUop))
  val OUT_uop = Vec(FUs.size, Decoupled(new ReadRegUop))
}

class Dispatcher(FUs: Seq[Seq[UInt]]) extends CoreModule {
  val io = IO(new DispatcherIO(FUs))

  val valid = FUs.map(fu => fu.map(_ === io.IN_uop.bits.fuType).reduce(_ || _))

  io.OUT_uop.zipWithIndex.foreach { case (out, i) =>
    out.valid := io.IN_uop.valid && valid(i)
    out.bits := io.IN_uop.bits
  }
  val outIndex = Mux1H((0 until FUs.size).map(i => io.OUT_uop(i).valid -> i.U))
  io.IN_uop.ready := io.OUT_uop(outIndex).ready
}