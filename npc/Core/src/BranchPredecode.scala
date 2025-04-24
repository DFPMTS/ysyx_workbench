import chisel3._
import chisel3.util._
import utils._
import scala.language.implicitConversions
import chisel3.util.experimental.decode.TruthTable
import chisel3.util.experimental.decode.decoder

class BranchInfo extends CoreBundle {
  val hasBr = Bool()
  val predecBrType = PredecBrType()
  val target = UInt(XLEN.W)

  def isIndirect = PredecBrType.isIndirect(predecBrType)
  def isJump = PredecBrType.isJump(predecBrType)
  def isDirectJump = PredecBrType.isDirectJump(predecBrType)
  def isBranch = PredecBrType.isBranch(predecBrType)
}

class BranchPredecodeIO extends CoreBundle {
  val IN_pc = Flipped(UInt(XLEN.W))
  val IN_inst = Flipped(UInt(32.W))
  val OUT_brInfo = new BranchInfo
}

class BranchPredecode extends CoreModule {
  val io = IO(new BranchPredecodeIO)
  implicit def uintToBitPat(x: UInt): BitPat = BitPat(x)
  def jal        = BitPat("b??????? ????? ????? ??? ????? 11011 11")
  def jalr       = BitPat("b??????? ????? ????? ??? ????? 11001 11")
  def beq        = BitPat("b??????? ????? ????? 000 ????? 11000 11")
  def bne        = BitPat("b??????? ????? ????? 001 ????? 11000 11")
  def blt        = BitPat("b??????? ????? ????? 100 ????? 11000 11")
  def bge        = BitPat("b??????? ????? ????? 101 ????? 11000 11")
  def bltu       = BitPat("b??????? ????? ????? 110 ????? 11000 11")
  def bgeu       = BitPat("b??????? ????? ????? 111 ????? 11000 11")
  
  // *
  val defaultOut: List[BitPat] = List(N, BitPat.dontCare(PredecBrType().getWidth))

  val lut: List[(BitPat, List[BitPat])] = List(
    jal   ->  List(Y, PredecBrType.JUMP),
    jalr  ->  List(Y, PredecBrType.IJUMP),
    beq   ->  List(Y, PredecBrType.BRANCH),
    bne   ->  List(Y, PredecBrType.BRANCH),
    blt   ->  List(Y, PredecBrType.BRANCH),
    bge   ->  List(Y, PredecBrType.BRANCH),
    bltu  ->  List(Y, PredecBrType.BRANCH),
    bgeu  ->  List(Y, PredecBrType.BRANCH)
  )
  
  def listToBitPat(l: List[BitPat]) = {
    l.reduceLeft(_ ## _)
  }

  def transformLUT(lut: List[(BitPat, List[BitPat])]): List[(BitPat, BitPat)] = {
    lut.map {
      case (key, value) =>
        (key, listToBitPat(value))
    }
  }

  val table = TruthTable(transformLUT(lut), listToBitPat(defaultOut))

  class predecodeBundle extends CoreBundle {
    val hasBr = Bool()
    val predecBrType = PredecBrType()
  }

  val rawBrInfo = decoder(io.IN_inst, table).asTypeOf(new predecodeBundle)
  val rawBrType = rawBrInfo.predecBrType
  val brType = WireInit(rawBrType)

  val rd = io.IN_inst(11, 7)
  val rs1 = io.IN_inst(19, 15)

  def isLinkReg(regNum: UInt): Bool = {
    regNum === 1.U || regNum === 5.U
  }
  when(rawBrType === PredecBrType.JUMP && isLinkReg(rd)) {
    brType := PredecBrType.CALL
  }
  when(rawBrType === PredecBrType.IJUMP) {
    val rdLink = isLinkReg(rd)
    val rs1Link = isLinkReg(rs1)
    val rdEqRs1 = rd === rs1
    when(rdLink && rs1Link) {
      brType := PredecBrType.ICALL
      // ! Not correct!
      // * See Table 3. Return-address Stack Prediction hints 
      // * encoded in the register operands of a JALR instruction
    } .elsewhen(rdLink && !rs1Link) {
      brType := PredecBrType.ICALL
    } .elsewhen(!rdLink && rs1Link) {
      brType := PredecBrType.RET
    }
  }

  val inst = io.IN_inst
  val immJ = Wire(SInt(XLEN.W))
  val immB = Wire(SInt(XLEN.W))
  immJ := Cat(inst(31), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W)).asSInt
  immB := Cat(inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W)).asSInt

  dontTouch(immJ)
  dontTouch(immB)

  val branchTarget = io.IN_pc + immB.asUInt
  val jumpTarget = io.IN_pc + immJ.asUInt

  io.OUT_brInfo.hasBr := rawBrInfo.hasBr
  io.OUT_brInfo.predecBrType := brType
  io.OUT_brInfo.target := Mux(rawBrInfo.predecBrType === PredecBrType.BRANCH, 
    branchTarget,
    jumpTarget
  )
}
