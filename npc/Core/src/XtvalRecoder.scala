import chisel3._
import chisel3.util._
import utils._
import chisel3.SpecifiedDirection.Flip

class XtvalRec extends CoreBundle {
  val tval = UInt(XLEN.W)
  val robPtr = RingBufferPtr(ROB_SIZE)
}

class XtvalRecoderIO extends CoreBundle {
  val IN_tval = Flipped(Valid(new XtvalRec))
  val OUT_tval = Valid(new XtvalRec)

  val IN_flush = Flipped(Bool())
}

class XtvalRecoder extends CoreModule {
  val io = IO(new XtvalRecoderIO)

  val xtvalValid = RegInit(false.B)
  val xtval = Reg(UInt(XLEN.W))
  val robPtr = Reg(new RingBufferPtr(ROB_SIZE))

  // * compare with current xtval, keep the earliest one
  when(io.IN_tval.valid && (!xtvalValid || io.IN_tval.bits.robPtr.isBefore(robPtr))) {
    xtval := io.IN_tval.bits.tval
    robPtr := io.IN_tval.bits.robPtr
    xtvalValid := true.B
  }

  when(io.IN_flush) {
    xtvalValid := false.B
  }

  io.OUT_tval.valid := xtvalValid
  io.OUT_tval.bits.tval := xtval
  io.OUT_tval.bits.robPtr := robPtr
}