import chisel3._
import chisel3.util._
import scala.reflect.internal.Mode

class IDU extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new IFU_Message))
    val out = Decoupled(new IDU_Out)
  })
  val counter      = RegInit(0.U(3.W))
  val insert       = Wire(Bool())
  val data_buffer  = RegEnable(io.in.bits, insert)
  val valid_buffer = RegEnable(io.in.valid, insert)
  counter := Mux(
    io.in.fire,
    0.U,
    Mux(counter === 0.U, 0.U, counter - 1.U)
  )
  insert := ~valid_buffer || (counter === 0.U && io.out.ready)

  io.in.ready  := insert
  io.out.valid := valid_buffer && counter === 0.U

  io.out.bits.pc   := data_buffer.pc
  io.out.bits.inst := data_buffer.inst

  val decode = Module(new Decode)
  decode.io.inst   := data_buffer.inst
  io.out.bits.ctrl := decode.io.ctrl

  val immgen = Module(new ImmGen)
  immgen.io.inst      := data_buffer.inst
  immgen.io.inst_type := decode.io.ctrl.inst_type
  io.out.bits.imm     := immgen.io.imm.asUInt
}