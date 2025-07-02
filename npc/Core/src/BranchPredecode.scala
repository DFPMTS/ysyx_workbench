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
  def jirl       = BitPat("b010011 ??????????? ????? ????? ?????") // JIRL rd, rj, offs
  def b          = BitPat("b010100 ??????????? ????? ????? ?????") // B offs
  def bl         = BitPat("b010101 ??????????? ????? ????? ?????") // BL offs
  def beq        = BitPat("b010110 ??????????? ????? ????? ?????") // BEQ rj, rd, offs
  def bne        = BitPat("b010111 ??????????? ????? ????? ?????") // BNE rj, rd, offs
  def blt        = BitPat("b011000 ??????????? ????? ????? ?????") // BLT rj, rd, offs
  def bge        = BitPat("b011001 ??????????? ????? ????? ?????") // BGE rj, rd, offs
  def bltu       = BitPat("b011010 ??????????? ????? ????? ?????") // BLTU rj, rd, offs
  def bgeu       = BitPat("b011011 ??????????? ????? ????? ?????") // BGEU rj, rd, offs
  
  // *
  val defaultOut: List[BitPat] = List(N, BitPat.dontCare(PredecBrType().getWidth))

  val lut: List[(BitPat, List[BitPat])] = List(
    b     ->  List(Y, PredecBrType.JUMP),
    jirl  ->  List(Y, PredecBrType.IJUMP),
    bl    ->  List(Y, PredecBrType.CALL),
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

  val rd = io.IN_inst(4, 0)
  val rs1 = io.IN_inst(9, 5)

  def isLinkReg(regNum: UInt): Bool = {
    // regNum === 1.U || regNum === 5.U
    regNum === 1.U
  }
  // when(rawBrType === PredecBrType.JUMP && isLinkReg(rd)) {
  //   brType := PredecBrType.CALL
  // }
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
  val offs16 = Wire(SInt(32.W))
  val offs26 = Wire(SInt(32.W))
  offs16 := Cat(inst(25, 10), 0.U(2.W)).asSInt
  offs26 := Cat(inst(9, 0), inst(25, 10), 0.U(2.W)).asSInt

  dontTouch(offs16)
  dontTouch(offs26)

  val branchTarget = io.IN_pc + offs16.asUInt
  val jumpTarget = io.IN_pc + offs26.asUInt

  io.OUT_brInfo.hasBr := rawBrInfo.hasBr
  io.OUT_brInfo.predecBrType := brType
  io.OUT_brInfo.target := Mux(rawBrInfo.predecBrType === PredecBrType.BRANCH, 
    branchTarget,
    jumpTarget
  )
}
