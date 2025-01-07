import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import utils._

class MULIO extends CoreBundle {
  val IN_readRegUop  = Flipped(Decoupled(new ReadRegUop))
  val OUT_writebackUop  = Valid(new WritebackUop)
  val IN_flush = Input(Bool())
}



class MUL extends CoreModule {
  val io = IO(new MULIO)
  
  val uop = Reg(Vec(IMUL_DELAY + 1, new WritebackUop))
  val uopValid = RegInit(VecInit(Seq.fill(IMUL_DELAY + 1)(false.B)))

  val dummyMUL = Module(new DummyMUL)
  dummyMUL.io.opcode := io.IN_readRegUop.bits.opcode
  dummyMUL.io.src1 := io.IN_readRegUop.bits.src1
  dummyMUL.io.src2 := io.IN_readRegUop.bits.src2

  uop(0).dest := Dest.ROB
  uop(0).prd := io.IN_readRegUop.bits.prd
  uop(0).data := dummyMUL.io.out
  uop(0).robPtr := io.IN_readRegUop.bits.robPtr
  uop(0).flag := 0.U
  uop(0).target := 0.U
  uopValid(0) := io.IN_readRegUop.valid

  for(i <- 1 until IMUL_DELAY + 1) {
    uop(i) := uop(i-1)
    uopValid(i) := uopValid(i-1)
  }

  when (io.IN_flush) {
    uopValid := VecInit(Seq.fill(IMUL_DELAY + 1)(false.B))
  }

  io.IN_readRegUop.ready := true.B
  io.OUT_writebackUop.bits := uop(IMUL_DELAY)
  io.OUT_writebackUop.valid := uopValid(IMUL_DELAY)
}

class DummyMUL extends HasBlackBoxInline{
  val io = IO(new Bundle {
    val opcode = Input(UInt(8.W))
    val src1 = Input(UInt(32.W))
    val src2 = Input(UInt(32.W))
    val out = Output(UInt(32.W))
  })
  setInline("DummyMUL.v",
    s"""
    |module DummyMUL(
    |  input  [7:0] opcode,
    |  input  [31:0] src1,
    |  input  [31:0] src2,
    |  output [31:0] out
    |);
    |  import "DPI-C" function int dummyMul(input byte opcode, input int src1, input int src2);
    |  assign out = dummyMul(opcode, src1, src2);
    |endmodule
    """.stripMargin)
}

class Multiplier extends CoreModule {
  val io = IO(new Bundle {
    val opcode = Input(UInt(8.W))
    val src1 = Input(UInt(32.W))
    val src2 = Input(UInt(32.W))
    val out = Output(UInt(32.W))
  })

  val src1Signed = io.opcode === MULOp.MUL || io.opcode === MULOp.MULH || io.opcode === MULOp.MULHSU
  val src2Signed = io.opcode === MULOp.MUL || io.opcode === MULOp.MULH 

  val a = io.src1 // * multiplicand
  val b = Cat(Mux(src2Signed, Fill(2, io.src2(31)), 0.U(2.W)), io.src2, 0.U(1.W)) // * multiplier
  
  val partialProd = (0 until 18).map { i =>
    val boothEnc = Module(new BoothEncoder)
    if(i == 0) {
      boothEnc.io.mulBits := Cat(b(1, 0), 0.U(1.W))
    } else {
      boothEnc.io.mulBits := b(i * 2 + 1, i * 2 - 1)
    }
    val boothCode = boothEnc.io.code
    val S = boothCode.sign
    val E = random.XNOR(boothCode.sign, b(31))
    val prod = WireInit(0.U(68.W))

  }
}

class BoothCode extends CoreBundle {
  val sign = Bool()
  val two = Bool()
  val one = Bool()
  val zero = Bool()
}

class BoothEncoderIO extends CoreBundle {
  val mulBits = Flipped(UInt(3.W)) // * multiplier bits
  val code = new BoothCode
}

class BoothEncoder extends CoreModule {
  val io = IO(new BoothEncoderIO)

  val lut = Seq(
    // *                     sign two one zero
    BitPat("b000") -> BitPat("b0   0   0   1"), // * +0
    BitPat("b001") -> BitPat("b0   0   1   0"), // * +1
    BitPat("b010") -> BitPat("b0   0   1   0"), // * +1
    BitPat("b011") -> BitPat("b0   1   0   0"), // * +2
    BitPat("b100") -> BitPat("b1   1   0   0"), // * -2
    BitPat("b101") -> BitPat("b1   0   1   0"), // * -1
    BitPat("b110") -> BitPat("b1   0   1   0"), // * -1
    BitPat("b111") -> BitPat("b1   0   0   1"), // * -0
  )
  val table = TruthTable(lut, BitPat("b000"))
  io.code := decoder(io.mulBits, table).asTypeOf(new BoothCode)
}

