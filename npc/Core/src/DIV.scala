import chisel3._
import chisel3.util._
import utils._

class DIVIO extends CoreBundle {
  val IN_readRegUop  = Flipped(Decoupled(new ReadRegUop))
  val OUT_writebackUop  = Valid(new WritebackUop)
  val OUT_idivBusy = Output(Bool())
  val IN_flush = Input(Bool())
}

class DIV extends CoreModule {
  val io = IO(new DIVIO)

  val uop = Reg(new WritebackUop)

  val sIdle :: sBusy :: Nil = Enum(2)
  val state = RegInit(sIdle)
  val counter = RegInit(0.U(8.W))
  val resultValid = counter === IDIV_DELAY.U
  state := MuxLookup(state, sIdle)(Seq(
    sIdle -> Mux(io.IN_readRegUop.valid, sBusy, sIdle),
    sBusy -> Mux(resultValid, sIdle, sBusy)
  ))
  
  when(state === sBusy) {
    counter := counter + 1.U
  }.otherwise{
    counter := 0.U
  }

  when(io.IN_flush) {
    counter := 0.U
    state := sIdle
  }


  val dummyDIV = Module(new DummyDIV)

  dummyDIV.io.opcode := io.IN_readRegUop.bits.opcode
  dummyDIV.io.src1 := io.IN_readRegUop.bits.src1
  dummyDIV.io.src2 := io.IN_readRegUop.bits.src2

  when(io.IN_readRegUop.valid && state === sIdle) {
    uop.dest := Dest.ROB
    uop.prd := io.IN_readRegUop.bits.prd
    uop.data := dummyDIV.io.out
    uop.robPtr := io.IN_readRegUop.bits.robPtr
    uop.flag := 0.U
    uop.target := 0.U
  }

  io.OUT_idivBusy := state === sBusy
  io.IN_readRegUop.ready := state === sIdle
  io.OUT_writebackUop.bits := uop
  io.OUT_writebackUop.valid := resultValid
}

class DummyDIV extends HasBlackBoxInline {
  val io = IO(new Bundle {
    val opcode = Input(UInt(8.W))
    val src1 = Input(UInt(32.W))
    val src2 = Input(UInt(32.W))
    val out = Output(UInt(32.W))
  })
  setInline("DummyDIV.v",
    s"""
    |module DummyDIV(
    |  input  [7:0] opcode,
    |  input  [31:0] src1,
    |  input  [31:0] src2,
    |  output [31:0] out
    |);
    |  import "DPI-C" function int dummyDiv(input byte opcode, input int src1, input int src2);
    |  assign out = dummyDiv(opcode, src1, src2);
    |endmodule
    """.stripMargin)
}