import chisel3._
import chisel3.util._
import utils._
import chisel3.SpecifiedDirection.Flip

class RASUpdate extends CoreBundle {
  val push = Bool()
  val target = UInt(XLEN.W)
}

class RASIO extends CoreBundle {
  val IN_bpUpdate = Flipped(Valid(new RASUpdate))
  val IN_commitUpdate = Flipped(Valid(new RASUpdate))
  val OUT_top = UInt(XLEN.W)
  val IN_flush = Flipped(Bool())
}

class RAS extends CoreModule {
  val io = IO(new RASIO)

  val top = RegInit(0.U(log2Up(RAS_SIZE).W))
  val ras = Reg(Vec(RAS_SIZE, UInt(XLEN.W)))

  val archTop = RegInit(0.U(log2Up(RAS_SIZE).W))
  val archRAS = Reg(Vec(RAS_SIZE, UInt(XLEN.W)))

  when(io.IN_commitUpdate.valid) {
    when(io.IN_commitUpdate.bits.push) {
      archRAS(archTop + 1.U) := io.IN_commitUpdate.bits.target
      archTop := archTop + 1.U
    }.otherwise {
      archTop := archTop - 1.U
    }
  }

  when(io.IN_flush) {
    top := archTop
    ras := archRAS
  }.otherwise {
    when(io.IN_bpUpdate.valid) {
      when(io.IN_bpUpdate.bits.push) {
        ras(top + 1.U) := io.IN_bpUpdate.bits.target
        top := top + 1.U
      }.otherwise {
        top := top - 1.U
      }
    }
  }

  io.OUT_top := ras(top)
}