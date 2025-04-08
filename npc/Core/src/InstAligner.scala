import chisel3._
import chisel3.util._
import utils._

class InstAlignerIO extends CoreBundle {
  val IN_fetchGroup = Flipped(Decoupled())
  val OUT_insts = Vec(ISSUE_WIDTH, Valid(new InstSignal))
  val OUT_valid = Output(Bool())
  val IN_flush = Input(Bool())
}

class InstAligner extends CoreModule {
  val io = IO(new InstAlignerIO)

  val inst = Reg(UInt(32.W))
  val valid = RegInit(false.B)

}
