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

  if(USE_DUMMY_MUL_DIV) { 
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
  } else {
    def abs(a: UInt, sign: Bool): (Bool, UInt) = {
      val s = a(XLEN - 1) && sign
      (s, Mux(s, -a, a))
    }
    val inValid = io.IN_readRegUop.valid
    val busy = RegInit(false.B)
    val counter = RegInit(0.U(8.W))

    val a = io.IN_readRegUop.bits.src1
    val b = io.IN_readRegUop.bits.src2
    val signed = !io.IN_readRegUop.bits.opcode(0)
    val divByZero = b === 0.U    
    val shiftReg = Reg(UInt((XLEN * 2 + 1).W))
    val hi = shiftReg(XLEN * 2, XLEN)
    val lo = shiftReg(XLEN - 1, 0)

    val (aSign, aVal) = abs(a, signed)
    val (bSign, bVal) = abs(b, signed)

    val aSignReg = RegEnable(aSign, inValid)
    val qSignReg = RegEnable((aSign ^ bSign) && !divByZero, inValid)
    val bReg = RegEnable(bVal, inValid)

    val isRem = Reg(Bool())
    val uopValid = RegInit(false.B)
    val uop = Reg(new WritebackUop)

    when(inValid) {
      busy := true.B
      counter := 0.U
      isRem := io.IN_readRegUop.bits.opcode(1)
      shiftReg := Cat(aVal, 0.U(1.W))
      uop.dest := Dest.ROB
      uop.prd := io.IN_readRegUop.bits.prd
      uop.robPtr := io.IN_readRegUop.bits.robPtr
      uop.flag := 0.U
      uop.target := 0.U
    }

    when(busy) {
      when(counter < XLEN.U) {
        val doSub = hi >= bReg
        shiftReg := Cat(Mux(doSub, hi - bReg, hi)(XLEN - 1, 0), lo, doSub)
        counter := counter + 1.U
      }
      when(counter === XLEN.U) {
        busy := false.B
      }
    }

    val r = hi(XLEN, 1)
    val resQ = Mux(qSignReg, -lo, lo)
    val resR = Mux(aSignReg, -r, r)

    uop.data := Mux(isRem, resR, resQ)

    uopValid := busy && counter === XLEN.U

    when(io.IN_flush) {
      busy := false.B
      uopValid := false.B
    }

    io.IN_readRegUop.ready := !busy

    io.OUT_writebackUop.valid := uopValid
    io.OUT_writebackUop.bits := uop
    io.OUT_idivBusy := busy
  }
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