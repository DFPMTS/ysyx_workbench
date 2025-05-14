import chisel3._
import chisel3.util._
import utils._

class LoadArbiterIO extends CoreBundle {
  val IN_PTWUop = Flipped(Decoupled(new PTWUop))
  val IN_AGUUop = Flipped(Decoupled(new AGUUop))
  val IN_stopPTWUop = Flipped(Bool())

  val OUT_AGUUop = Decoupled(new AGUUop)
}

class LoadArbiter extends CoreModule {
  val io = IO(new LoadArbiterIO)

  when(io.IN_stopPTWUop || io.IN_AGUUop.valid) {
    io.IN_PTWUop.ready := false.B
    io.IN_AGUUop <> io.OUT_AGUUop
  }.otherwise {
    io.IN_AGUUop.ready := false.B
    io.OUT_AGUUop.bits.prd := ZERO
    io.OUT_AGUUop.bits.addr := io.IN_PTWUop.bits.addr
    io.OUT_AGUUop.bits.wdata := ZERO

    io.OUT_AGUUop.bits.fuType := FuType.LSU
    io.OUT_AGUUop.bits.opcode := LSUOp.LW    

    io.OUT_AGUUop.bits.dest := Dest.PTW
    io.OUT_AGUUop.bits.robPtr := ZERO.asTypeOf(io.OUT_AGUUop.bits.robPtr)
    io.OUT_AGUUop.bits.ldqPtr := ZERO.asTypeOf(io.OUT_AGUUop.bits.ldqPtr)
    io.OUT_AGUUop.bits.stqPtr := io.IN_PTWUop.bits.stqPtr
    
    io.OUT_AGUUop.bits.predTarget := ZERO
    io.OUT_AGUUop.bits.compressed := false.B

    io.OUT_AGUUop.bits.isInternalMMIO := false.B
    io.OUT_AGUUop.bits.isUncached := false.B

    io.OUT_AGUUop.valid := io.IN_PTWUop.valid    
    io.IN_PTWUop.ready := io.OUT_AGUUop.ready
  }
}