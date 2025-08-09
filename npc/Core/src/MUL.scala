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
  
  if(USE_DUMMY_MUL_DIV) {
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
  } else {
    val multiplier = (if (USE_DSP_MULTIPLIER) Module(new DSP48E1Multiplier) else Module(new Multiplier))
    multiplier.io.opcode := io.IN_readRegUop.bits.opcode
    multiplier.io.src1 := io.IN_readRegUop.bits.src1
    multiplier.io.src2 := io.IN_readRegUop.bits.src2
    val uop = Reg(Vec(IMUL_DELAY + 1, new WritebackUop))
    val uopValid = RegInit(VecInit(Seq.fill(IMUL_DELAY + 1)(false.B)))

    uop(0).dest := Dest.ROB
    uop(0).prd := io.IN_readRegUop.bits.prd
    uop(0).robPtr := io.IN_readRegUop.bits.robPtr
    uop(0).flag := 0.U
    uop(0).target := 0.U
    uopValid(0) := io.IN_readRegUop.valid

    for(i <- 1 until IMUL_DELAY + 1) {
      uop(i) := uop(i-1)
      uopValid(i) := uopValid(i-1)
    }

    uop(IMUL_DELAY).data := multiplier.io.out

    when (io.IN_flush) {
      uopValid := VecInit(Seq.fill(IMUL_DELAY + 1)(false.B))
    }
    io.IN_readRegUop.ready := true.B
    io.OUT_writebackUop.valid := uopValid(IMUL_DELAY)
    io.OUT_writebackUop.bits := uop(IMUL_DELAY)
  }
}

class AbstractMultiplier extends CoreModule {
  val io = IO(new Bundle {
    val opcode = Input(UInt(8.W))
    val src1 = Input(UInt(32.W))
    val src2 = Input(UInt(32.W))
    val out = Output(UInt(32.W))
  })
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

class Multiplier extends AbstractMultiplier {

  val aSigned = io.opcode === MULOp.MUL || io.opcode === MULOp.MULH || io.opcode === MULOp.MULHSU
  val bSigned = io.opcode === MULOp.MUL || io.opcode === MULOp.MULH 
  val isH = io.opcode === MULOp.MULH || io.opcode === MULOp.MULHSU || io.opcode === MULOp.MULHU
  val isHReg2 = ShiftRegister(isH, 2)

  val a = io.src1 // * multiplicand
  val b = Cat(Mux(bSigned, Fill(2, io.src2(XLEN-1)), 0.U(2.W)), io.src2) // * multiplier
  
  // * Stage 0: vector of partial products, 
  val partialProd = WireInit(VecInit(Seq.fill(XLEN/2+1)(VecInit(Seq.fill(2*XLEN+5)(false.B)))))
  (0 until XLEN/2+1).map { i =>
    val boothEnc = Module(new BoothEncoder)
    if(i == 0) {
      boothEnc.io.mulBits := Cat(b(1, 0), 0.U(1.W))
    } else {
      boothEnc.io.mulBits := b(i * 2 + 1, i * 2 - 1)
    }
    val boothCode = boothEnc.io.code
    val S = boothCode.sign
    val E = random.XNOR(boothCode.sign, Mux(boothCode.zero, false.B, a(XLEN-1) && aSigned))
    dontTouch(S)
    dontTouch(E)
    val prod = WireInit(VecInit(Seq.fill(2*XLEN+5)(false.B)))
    val shift = i * 2
    // * generate the XLEN+1 bit shifted multiplicand
    // * [32, 0]
    val shiftedMultiplicand = (0 until XLEN+1).map{j => 
        if(j == 0) {
          Mux1H(Seq(boothCode.one -> a(0),
                    boothCode.two -> 0.U(1.W),
                    boothCode.zero -> 0.U(1.W)))
        } else if (j == XLEN) {
          Mux1H(Seq(boothCode.one -> (a(XLEN-1) & aSigned), // * sign extend
                    boothCode.two -> a(XLEN-1),
                    boothCode.zero -> 0.U(1.W)))
        } else {
          Mux1H(Seq(boothCode.one -> a(j),
                    boothCode.two -> a(j - 1),
                    boothCode.zero -> 0.U(1.W)))
        }    
      }
    // * put the flipped (if needed) shifted multiplicand into the product
    (0 until XLEN+1).map{j => 
      prod(shift + j) := Mux(S, ~shiftedMultiplicand(j), shiftedMultiplicand(j))
    }

    if(i == 0) {
      prod(shift + XLEN + 1) := ~E
      prod(shift + XLEN + 2) := ~E
      prod(shift + XLEN + 3) := E
    } else {
      prod(shift + XLEN + 1) := E
      prod(shift + XLEN + 2) := 1.U
    }

    
    if(i < XLEN/2) {
      partialProd(i + 1)(i * 2) := S
    }
    for(j <- 0 until XLEN + 4) {
      partialProd(i)(shift + j) := Mux((i == XLEN/2).B && bSigned, 0.U, prod(shift + j))
    }
  }
  val sum = WireInit(0.U((2*XLEN).W))
  val partialProdU = VecInit(partialProd.map(_.asUInt))
  for (i <- 0 until XLEN/2+1) {    
    dontTouch(partialProdU(i))
  }
  val partialProdUReg = RegNext(partialProdU)

  // * Stage 2: Wallace tree
  val wallaceTree = Module(new WallaceTree17x69)
  wallaceTree.io.in := partialProdUReg
  val outAReg = RegNext(wallaceTree.io.outA)
  val outBReg = RegNext(wallaceTree.io.outB)

  // * Stage 3: final sum
  sum := outAReg + outBReg
  io.out := Mux(isHReg2, sum(2*XLEN-1, XLEN), sum(XLEN-1, 0))
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
  val table = TruthTable(lut, BitPat("b0000"))
  io.code := decoder(io.mulBits, table).asTypeOf(new BoothCode)
}

class WallaceTree17x69 extends CoreModule {
  val Len = 69
  val io = IO(new Bundle {
    val in = Flipped(Vec(17, UInt(Len.W)))
    val outA = UInt(Len.W)
    val outB = UInt(Len.W)
  })
  def CSA(a: UInt, b: UInt, c: UInt): Seq[UInt] = {
    val sum = Wire(UInt(Len.W))
    val carry = Wire(UInt(Len.W))
    val aXorB = a ^ b
    sum := aXorB ^ c
    carry := Cat((a & b) | (aXorB & c), 0.U(1.W))(Len - 1, 0)
    Seq(sum, carry)
  }
  // * Wallace Tree of 17 numbers
  // * Level 1 17 -> 12
  val level1 = Seq.tabulate(5) { i => CSA(io.in(3 * i), io.in(3 * i + 1), io.in(3 * i + 2)) }.flatten :+ io.in(15) :+ io.in(16)
  // * Level2 12 -> 8
  val level2 = Seq.tabulate(4) { i => CSA(level1(3 * i), level1(3 * i + 1), level1(3 * i + 2)) }.flatten
  // * Level3 8 -> 6
  val level3 = Seq.tabulate(2) { i => CSA(level2(3 * i), level2(3 * i + 1), level2(3 * i + 2)) }.flatten :+ level2(6) :+ level2(7)
  // * Level4 6 -> 4
  val level4 = Seq.tabulate(2) { i => CSA(level3(3 * i), level3(3 * i + 1), level3(3 * i + 2)) }.flatten
  // * Level5 4 -> 3
  val level5 = Seq.tabulate(1) { i => CSA(level4(3 * i), level4(3 * i + 1), level4(3 * i + 2)) }.flatten :+ level4(3)
  // * Level6 3 -> 2
  val level6 = Seq.tabulate(1) { i => CSA(level5(3 * i), level5(3 * i + 1), level5(3 * i + 2)) }.flatten

  io.outA := level6(0)
  io.outB := level6(1)
}

class DSP48E1Multiplier extends AbstractMultiplier {

  val aNeg = MULOp.isANegative(io.opcode, io.src1)
  val bNeg = MULOp.isBNegative(io.opcode, io.src2)
  val isH = MULOp.isHigh(io.opcode)
  val isResultNeg = MULOp.isResultNegative(io.opcode, aNeg, bNeg)

  val isHReg = RegNext(isH)
  val isResultNegReg = RegNext(isResultNeg)

  val aAbs = Mux(aNeg, ~io.src1 + 1.U, io.src1)
  val bAbs = Mux(bNeg, ~io.src2 + 1.U, io.src2)

  val PartVec = Vec(2, UInt((XLEN / 2).W))
  val aAbsVec = WireInit(aAbs.asTypeOf(PartVec))
  val bAbsVec = WireInit(bAbs.asTypeOf(PartVec))

  // * Stage 0
  val partialProdReg = Reg(Vec(2, Vec(2, UInt(XLEN.W))))
  val shiftedPartialProd = Wire(Vec(2, Vec(2, UInt((2 * XLEN).W))))
  val finalSum = Wire(UInt((2 * XLEN).W))

  def DSP48Mul16bit(a: UInt, b: UInt): UInt = {
    a * b
  }

  def shiftPartialProd(prod: UInt, shift: Int): UInt = {
    Cat(prod, 0.U((shift * (XLEN / 2)).W))
  }

  for (i <- 0 until 2) {
    for (j <- 0 until 2) {
      // * Stage 0 Reg
      partialProdReg(i)(j) := DSP48Mul16bit(aAbsVec(i), bAbsVec(j))
      shiftedPartialProd(i)(j) := shiftPartialProd(partialProdReg(i)(j), i + j)
    }
  }

  val unsignedSum = shiftedPartialProd.flatten.reduce(_ + _)
  finalSum := Mux(isResultNegReg, ~unsignedSum + 1.U, unsignedSum)

  io.out := Mux(isHReg, finalSum(2 * XLEN - 1, XLEN), finalSum(XLEN - 1, 0))
}
