import chisel3._
import chisel3.util._
import chisel3.util.experimental._
import chisel3.util.experimental.decode.TruthTable
import chisel3.util.experimental.decode.decoder
import scala.language.implicitConversions
import utils._
import FuType.FLAG

class DecodeSignal extends Bundle with HasDecodeConfig{
  val invalid  = Bool()
  val regWe    = Bool()
  val src1Type = UInt(2.W)
  val src2Type = UInt(2.W)  
  val fuType   = UInt(FuTypeWidth.W)
  val opcode   = UInt(OpcodeWidth.W)
  val immType = UInt(ImmTypeWidth.W)
  val lockBackend = Bool()
}

// class Decode extends CoreModule {
//   val io = IO(new Bundle {
//     val inst    = Input(UInt(32.W))
//     val signals = Output(new DecodeSignal)
//   })
//   implicit def uintToBitPat(x: UInt): BitPat = BitPat(x)
//   def lui        = BitPat("b??????? ????? ????? ??? ????? 01101 11")
//   def auipc      = BitPat("b??????? ????? ????? ??? ????? 00101 11")
//   def jal        = BitPat("b??????? ????? ????? ??? ????? 11011 11")
//   def jalr       = BitPat("b??????? ????? ????? ??? ????? 11001 11")
//   def beq        = BitPat("b??????? ????? ????? 000 ????? 11000 11")
//   def bne        = BitPat("b??????? ????? ????? 001 ????? 11000 11")
//   def blt        = BitPat("b??????? ????? ????? 100 ????? 11000 11")
//   def bge        = BitPat("b??????? ????? ????? 101 ????? 11000 11")
//   def bltu       = BitPat("b??????? ????? ????? 110 ????? 11000 11")
//   def bgeu       = BitPat("b??????? ????? ????? 111 ????? 11000 11")
//   def lb         = BitPat("b??????? ????? ????? 000 ????? 00000 11")
//   def lh         = BitPat("b??????? ????? ????? 001 ????? 00000 11")
//   def lw         = BitPat("b??????? ????? ????? 010 ????? 00000 11")
//   def lbu        = BitPat("b??????? ????? ????? 100 ????? 00000 11")
//   def lhu        = BitPat("b??????? ????? ????? 101 ????? 00000 11")
//   def sb         = BitPat("b??????? ????? ????? 000 ????? 01000 11")
//   def sh         = BitPat("b??????? ????? ????? 001 ????? 01000 11")
//   def sw         = BitPat("b??????? ????? ????? 010 ????? 01000 11")
//   def addi       = BitPat("b??????? ????? ????? 000 ????? 00100 11")
//   def slti       = BitPat("b??????? ????? ????? 010 ????? 00100 11")
//   def sltiu      = BitPat("b??????? ????? ????? 011 ????? 00100 11")
//   def xori       = BitPat("b??????? ????? ????? 100 ????? 00100 11")
//   def ori        = BitPat("b??????? ????? ????? 110 ????? 00100 11")
//   def andi       = BitPat("b??????? ????? ????? 111 ????? 00100 11")
//   def slli       = BitPat("b0000000 ????? ????? 001 ????? 00100 11")
//   def srli       = BitPat("b0000000 ????? ????? 101 ????? 00100 11")
//   def srai       = BitPat("b0100000 ????? ????? 101 ????? 00100 11")
//   def add        = BitPat("b0000000 ????? ????? 000 ????? 01100 11")
//   def sub        = BitPat("b0100000 ????? ????? 000 ????? 01100 11")
//   def sll        = BitPat("b0000000 ????? ????? 001 ????? 01100 11")
//   def slt        = BitPat("b0000000 ????? ????? 010 ????? 01100 11")
//   def sltu       = BitPat("b0000000 ????? ????? 011 ????? 01100 11")
//   def xor        = BitPat("b0000000 ????? ????? 100 ????? 01100 11")
//   def srl        = BitPat("b0000000 ????? ????? 101 ????? 01100 11")
//   def sra        = BitPat("b0100000 ????? ????? 101 ????? 01100 11")
//   def or         = BitPat("b0000000 ????? ????? 110 ????? 01100 11")
//   def and        = BitPat("b0000000 ????? ????? 111 ????? 01100 11")
//   def fence_i    = BitPat("b??????? ????? ????? 001 ????? 00011 11")
//   def ecall      = BitPat("b0000000 00000 00000 000 00000 11100 11")
//   def ebreak     = BitPat("b0000000 00001 00000 000 00000 11100 11")
//   def csrrw      = BitPat("b??????? ????? ????? 001 ????? 11100 11")
//   def csrrs      = BitPat("b??????? ????? ????? 010 ????? 11100 11")
//   def csrrc      = BitPat("b??????? ????? ????? 011 ????? 11100 11")
//   def csrrwi     = BitPat("b??????? ????? ????? 101 ????? 11100 11")
//   def csrrsi     = BitPat("b??????? ????? ????? 110 ????? 11100 11")
//   def csrrci     = BitPat("b??????? ????? ????? 111 ????? 11100 11")
//   def mret       = BitPat("b0011000 00010 00000 000 00000 11100 11")
//   def sret       = BitPat("b0001000 00010 00000 000 00000 11100 11")
//   def fence      = BitPat("b???? ???? ???? ????? 000 ????? 00011 11")
//   def sfence_vma = BitPat("b0001001 ????? ????? 000 00000 11100 11")
//   def wfi        = BitPat("b0001000 00101 00000 000 00000 11100 11")
//   // * RV32M
//   def mul        = BitPat("b0000001 ????? ????? 000 ????? 01100 11")
//   def mulh       = BitPat("b0000001 ????? ????? 001 ????? 01100 11")
//   def mulhsu     = BitPat("b0000001 ????? ????? 010 ????? 01100 11")
//   def mulhu      = BitPat("b0000001 ????? ????? 011 ????? 01100 11")
//   def div        = BitPat("b0000001 ????? ????? 100 ????? 01100 11")
//   def divu       = BitPat("b0000001 ????? ????? 101 ????? 01100 11")
//   def rem        = BitPat("b0000001 ????? ????? 110 ????? 01100 11")
//   def remu       = BitPat("b0000001 ????? ????? 111 ????? 01100 11")
//   // * RV32A
//   def lr_w       = BitPat("b00010?? 00000 ????? 010 ????? 0101111")
//   def sc_w       = BitPat("b00011?? ????? ????? 010 ????? 0101111")
//   def amoswap_w  = BitPat("b00001?? ????? ????? 010 ????? 0101111")
//   def amoadd_w   = BitPat("b00000?? ????? ????? 010 ????? 0101111")
//   def amoxor_w   = BitPat("b00100?? ????? ????? 010 ????? 0101111")
//   def amoand_w   = BitPat("b01100?? ????? ????? 010 ????? 0101111")
//   def amoor_w    = BitPat("b01000?? ????? ????? 010 ????? 0101111")
//   def amomin_w   = BitPat("b10000?? ????? ????? 010 ????? 0101111")
//   def amomax_w   = BitPat("b10100?? ????? ????? 010 ????? 0101111")
//   def amominu_w  = BitPat("b11000?? ????? ????? 010 ????? 0101111")
//   def amomaxu_w  = BitPat("b11100?? ????? ????? 010 ????? 0101111")
  


//   val defaultCtrl: List[BitPat] = List(Y, N, BitPat.dontCare(2), BitPat.dontCare(2), BitPat.dontCare(FuTypeWidth), BitPat.dontCare(OpcodeWidth), ImmType.X, N)
//   val lut: List[(BitPat, List[BitPat])] = List(
// lui        -> List(N, Y, SrcType.ZERO, SrcType.IMM,  FuType.ALU,  ALUOp.ADD,                  ImmType.U, N),
// auipc      -> List(N, Y, SrcType.PC,   SrcType.IMM,  FuType.BRU,  ALUOp.ADD,                  ImmType.U, N),
// jal        -> List(N, Y, SrcType.PC,   SrcType.IMM,  FuType.BRU,  BRUOp.JAL,                  ImmType.J, N),
// jalr       -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.BRU,  BRUOp.JALR,                 ImmType.I, N),
// beq        -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.BRU,  BRUOp.BEQ,                  ImmType.B, N),
// bne        -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.BRU,  BRUOp.BNE,                  ImmType.B, N),
// blt        -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.BRU,  BRUOp.BLT,                  ImmType.B, N),
// bge        -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.BRU,  BRUOp.BGE,                  ImmType.B, N),
// bltu       -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.BRU,  BRUOp.BLTU,                 ImmType.B, N),
// bgeu       -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.BRU,  BRUOp.BGEU,                 ImmType.B, N),
// lb         -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.LSU,  LSUOp.LB,                   ImmType.I, N),
// lh         -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.LSU,  LSUOp.LH,                   ImmType.I, N),
// lw         -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.LSU,  LSUOp.LW,                   ImmType.I, N),
// lbu        -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.LSU,  LSUOp.LBU,                  ImmType.I, N),
// lhu        -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.LSU,  LSUOp.LHU,                  ImmType.I, N),
// sb         -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.LSU,  LSUOp.SB,                   ImmType.S, N),
// sh         -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.LSU,  LSUOp.SH,                   ImmType.S, N),
// sw         -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.LSU,  LSUOp.SW,                   ImmType.S, N),
// addi       -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.ADD,                  ImmType.I, N),
// slti       -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.LT,                   ImmType.I, N),
// sltiu      -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.LTU,                  ImmType.I, N),
// xori       -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.XOR,                  ImmType.I, N),
// ori        -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.OR,                   ImmType.I, N),
// andi       -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.AND,                  ImmType.I, N),
// slli       -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.LEFT,                 ImmType.I, N),
// srli       -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.RIGHT,                ImmType.I, N),
// srai       -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.ARITH,                ImmType.I, N),
// add        -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.ADD,                  ImmType.X, N),
// sub        -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.SUB,                  ImmType.X, N),
// sll        -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.LEFT,                 ImmType.X, N),
// slt        -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.LT,                   ImmType.X, N),
// sltu       -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.LTU,                  ImmType.X, N),
// xor        -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.XOR,                  ImmType.X, N),
// srl        -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.RIGHT,                ImmType.X, N),
// sra        -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.ARITH,                ImmType.X, N),
// or         -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.OR,                   ImmType.X, N),
// and        -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.AND,                  ImmType.X, N),
// fence      -> List(N, N, SrcType.ZERO, SrcType.ZERO, FuType.FLAG, DecodeFlagOp.NONE,          ImmType.X, N), // TODO
// fence_i    -> List(N, N, SrcType.ZERO, SrcType.ZERO, FuType.FLAG, DecodeFlagOp.FENCE_I,       ImmType.X, Y), // TODO
// sfence_vma -> List(N, N, SrcType.ZERO, SrcType.ZERO, FuType.FLAG, DecodeFlagOp.SFENCE_VMA,    ImmType.X, Y),
// ecall      -> List(N, N, SrcType.ZERO, SrcType.ZERO, FuType.FLAG, DecodeFlagOp.ECALL,         ImmType.X, N),
// ebreak     -> List(N, N, SrcType.ZERO, SrcType.ZERO, FuType.FLAG, DecodeFlagOp.EBREAK,        ImmType.X, N),
// csrrw      -> List(N, Y, SrcType.REG,  SrcType.ZERO, FuType.CSR,  CSROp.CSRRW,                ImmType.I, N),
// csrrs      -> List(N, Y, SrcType.REG,  SrcType.ZERO, FuType.CSR,  CSROp.CSRRS,                ImmType.I, N),
// csrrc      -> List(N, Y, SrcType.REG,  SrcType.ZERO, FuType.CSR,  CSROp.CSRRC,                ImmType.I, N),
// csrrwi     -> List(N, Y, SrcType.REG,  SrcType.ZERO, FuType.CSR,  CSROp.CSRRWI,               ImmType.I, N),
// csrrsi     -> List(N, Y, SrcType.REG,  SrcType.ZERO, FuType.CSR,  CSROp.CSRRSI,               ImmType.I, N),
// csrrci     -> List(N, Y, SrcType.REG,  SrcType.ZERO, FuType.CSR,  CSROp.CSRRCI,               ImmType.I, N),
// mret       -> List(N, N, SrcType.ZERO, SrcType.ZERO, FuType.FLAG, DecodeFlagOp.MRET,          ImmType.X, N),
// sret       -> List(N, N, SrcType.ZERO, SrcType.ZERO, FuType.FLAG, DecodeFlagOp.SRET,          ImmType.X, N),
// wfi        -> List(N, N, SrcType.ZERO, SrcType.ZERO, FuType.FLAG, DecodeFlagOp.NONE,          ImmType.X, N), // TODO
// mul        -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.MUL,  MULOp.MUL,                  ImmType.X, N),// * RV32M begin
// mulh       -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.MUL,  MULOp.MULH,                 ImmType.X, N),
// mulhsu     -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.MUL,  MULOp.MULHSU,               ImmType.X, N),
// mulhu      -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.MUL,  MULOp.MULHU,                ImmType.X, N),
// div        -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.DIV,  DIVOp.DIV,                  ImmType.X, N),
// divu       -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.DIV,  DIVOp.DIVU,                 ImmType.X, N),
// rem        -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.DIV,  DIVOp.REM,                  ImmType.X, N),
// remu       -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.DIV,  DIVOp.REMU,                 ImmType.X, N),
// lr_w       -> List(N, Y, SrcType.REG,  SrcType.ZERO, FuType.AMO,  AMOOp.LR_W,                 ImmType.X, Y),// * RV32A begin
// sc_w       -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.AMO,  AMOOp.SC_W,                 ImmType.X, Y),
// amoswap_w  -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.AMO,  AMOOp.SWAP_W,               ImmType.X, Y),
// amoadd_w   -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.AMO,  AMOOp.ADD_W,                ImmType.X, Y),
// amoxor_w   -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.AMO,  AMOOp.XOR_W,                ImmType.X, Y),
// amoand_w   -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.AMO,  AMOOp.AND_W,                ImmType.X, Y),
// amoor_w    -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.AMO,  AMOOp.OR_W,                 ImmType.X, Y),
// amomin_w   -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.AMO,  AMOOp.MIN_W,                ImmType.X, Y),
// amomax_w   -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.AMO,  AMOOp.MAX_W,                ImmType.X, Y),
// amominu_w  -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.AMO,  AMOOp.MINU_W,               ImmType.X, Y),
// amomaxu_w  -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.AMO,  AMOOp.MAXU_W,               ImmType.X, Y),


//   )
//   def listToBitPat(l: List[BitPat]) = {
//     l.reduceLeft(_ ## _)
//   }

//   def transformLUT(lut: List[(BitPat, List[BitPat])]): List[(BitPat, BitPat)] = {
//     lut.map {
//       case (key, value) =>
//         (key, listToBitPat(value))
//     }
//   }

//   val table = TruthTable(transformLUT(lut), listToBitPat(defaultCtrl))
//   io.signals := decoder(io.inst, table).asTypeOf(new DecodeSignal)
// }


class LA32RDecode extends CoreModule {
  val io = IO(new Bundle {
    val inst    = Input(UInt(32.W))
    val signals = Output(new DecodeSignal)
  })

  implicit def uintToBitPat(x: UInt): BitPat = BitPat(x)
  def cpucfg     = BitPat("b00000000000000000  11011 ????? ?????")
  def rdcnt_w    = BitPat("b00000000000000000  11000 ????? ?????") // rd = 0 -> rdcntid.w, rj = 0 -> rdcntvl.w
  def rdcntvh_w  = BitPat("b00000000000000000  11001 00000 ?????")
  def add_w      = BitPat("b00000000000100000  ????? ????? ?????") // ADD.W rd, rj, rk
  def sub_w      = BitPat("b00000000000100010  ????? ????? ?????") // SUB.W rd, rj, rk
  def slt        = BitPat("b00000000000100100  ????? ????? ?????") // SLT rd, rj, rk
  def sltu       = BitPat("b00000000000100101  ????? ????? ?????") // SLTU rd, rj, rk
  def nor        = BitPat("b00000000000101000  ????? ????? ?????") // NOR rd, rj, rk
  def and        = BitPat("b00000000000101001  ????? ????? ?????") // AND rd, rj, rk
  def or         = BitPat("b00000000000101010  ????? ????? ?????") // OR rd, rj, rk
  def xor        = BitPat("b00000000000101011  ????? ????? ?????") // XOR rd, rj, rk
  def sll_w      = BitPat("b00000000000101110  ????? ????? ?????") // SLL.W rd, rj, rk
  def srl_w      = BitPat("b00000000000101111  ????? ????? ?????") // SRL.W rd, rj, rk
  def sra_w      = BitPat("b00000000000110000  ????? ????? ?????") // SRA.W rd, rj, rk
  def mul_w      = BitPat("b00000000000111000  ????? ????? ?????") // MUL.W rd, rj, rk
  def mulh_w     = BitPat("b00000000000111001  ????? ????? ?????") // MULH.W rd, rj, rk
  def mulh_wu    = BitPat("b00000000000111010  ????? ????? ?????") // MULH.WU rd, rj, rk
  def div_w      = BitPat("b00000000001000000  ????? ????? ?????") // DIV.W rd, rj, rk
  def mod_w      = BitPat("b00000000001000001  ????? ????? ?????") // MOD.W rd, rj, rk
  def div_wu     = BitPat("b00000000001000010  ????? ????? ?????") // DIV.WU rd, rj, rk
  def mod_wu     = BitPat("b00000000001000011  ????? ????? ?????") // MOD.WU rd, rj, rk
  def break      = BitPat("b00000000001010100  ????? ????? ?????") // BREAK code
  def syscall    = BitPat("b00000000001010110  ????? ????? ?????") // SYSCALL code
  def slli_w     = BitPat("b00000000010000 001 ????? ????? ?????") // SLLI.W rd, rj, ui5
  def srli_w     = BitPat("b00000000010001 001 ????? ????? ?????") // SRLI.W rd, rj, ui5
  def srai_w     = BitPat("b00000000010010 001 ????? ????? ?????") // SRAI.W rd, rj, ui5

  // Immediate arithmetic/logical instructions
  def slti       = BitPat("b00000001000 ??????? ????? ????? ?????") // SLTI rd, rj, si12
  def sltui      = BitPat("b00000001001 ??????? ????? ????? ?????") // SLTUI rd, rj, si12
  def addi_w     = BitPat("b00000001010 ??????? ????? ????? ?????") // ADDI.W rd, rj, si12
  def andi       = BitPat("b00000001101 ??????? ????? ????? ?????") // ANDI rd, rj, ui12
  def ori        = BitPat("b00000001110 ??????? ????? ????? ?????") // ORI rd, rj, ui12
  def xori       = BitPat("b00000001111 ??????? ????? ????? ?????") // XORI rd, rj, ui12

  // CSR instructions
  def csrop      = BitPat("b00000100 ?????????????? ????? ?????") // rj=0 -> CSRRD, rj=1 -> CSRWR, rj!=0,1 -> CSRXCHG

  // Cache and TLB instructions
  def cacop      = BitPat("b0000011000 ??????? ????? ????? ?????") // CACOP code, rj, si12
  def tlbsrch    = BitPat("b0000011001 0010000 01010 00000 00000") // TLBSRCH
  def tlbrd      = BitPat("b0000011001 0010000 01011 00000 00000") // TLBRD
  def tlbwr      = BitPat("b0000011001 0010000 01100 00000 00000") // TLBWR
  def tlbfill    = BitPat("b0000011001 0010000 01101 00000 00000") // TLBFILL
  def ertn       = BitPat("b0000011001 0010000 01110 00000 00000") // ERTN
  def idle       = BitPat("b0000011001 0010001 ????? ????? ?????") // IDLE level
  def invtlb     = BitPat("b0000011001 0010011 ????? ????? ?????") // INVTLB op, rj, rk

  // Upper immediate instructions
  def lu12i_w    = BitPat("b0001010 ?????????? ????? ????? ?????") // LU12I.W rd, si20
  def pcaddu12i  = BitPat("b0001110 ?????????? ????? ????? ?????") // PCADDU12I rd, si20

  // Load-link/Store-conditional
  def ll_w       = BitPat("b00100000 ????????? ????? ????? ?????") // LL.W rd, rj, si14
  def sc_w       = BitPat("b00100001 ????????? ????? ????? ?????") // SC.W rd, rj, si14

  // Load instructions
  def ld_b       = BitPat("b00101000 00 ??????? ????? ????? ?????") // LD.B rd, rj, si12
  def ld_h       = BitPat("b00101000 01 ??????? ????? ????? ?????") // LD.H rd, rj, si12
  def ld_w       = BitPat("b00101000 10 ??????? ????? ????? ?????") // LD.W rd, rj, si12
  def ld_bu      = BitPat("b00101010 00 ??????? ????? ????? ?????") // LD.BU rd, rj, si12
  def ld_hu      = BitPat("b00101010 01 ??????? ????? ????? ?????") // LD.HU rd, rj, si12

  // Store instructions
  def st_b       = BitPat("b00101001 00 ??????? ????? ????? ?????") // ST.B rd, rj, si12
  def st_h       = BitPat("b00101001 01 ??????? ????? ????? ?????") // ST.H rd, rj, si12
  def st_w       = BitPat("b00101001 10 ??????? ????? ????? ?????") // ST.W rd, rj, si12

  // Prefetch and barrier instructions
  def preld      = BitPat("b00101010 11 ??????? ????? ????? ?????") // PRELD hint, rj, si12
  def dbar       = BitPat("b00111000011100100   ????? ????? ?????") // DBAR hint
  def ibar       = BitPat("b00111000011100101   ????? ????? ?????") // IBAR hint

  // Jump and branch instructions
  def jirl       = BitPat("b010011 ??????????? ????? ????? ?????") // JIRL rd, rj, offs
  def b          = BitPat("b010100 ??????????? ????? ????? ?????") // B offs
  def bl         = BitPat("b010101 ??????????? ????? ????? ?????") // BL offs
  def beq        = BitPat("b010110 ??????????? ????? ????? ?????") // BEQ rj, rd, offs
  def bne        = BitPat("b010111 ??????????? ????? ????? ?????") // BNE rj, rd, offs
  def blt        = BitPat("b011000 ??????????? ????? ????? ?????") // BLT rj, rd, offs
  def bge        = BitPat("b011001 ??????????? ????? ????? ?????") // BGE rj, rd, offs
  def bltu       = BitPat("b011010 ??????????? ????? ????? ?????") // BLTU rj, rd, offs
  def bgeu       = BitPat("b011011 ??????????? ????? ????? ?????") // BGEU rj, rd, offs

  val defaultCtrl: List[BitPat] = List(Y, N, BitPat.dontCare(2), BitPat.dontCare(2), BitPat.dontCare(FuTypeWidth), BitPat.dontCare(OpcodeWidth), ImmType.X, N)
  val lut: List[(BitPat, List[BitPat])] = List(
cpucfg     -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.CSR,  CSROp.CPUCFG,          LA32RImmType.X,      Y),
rdcnt_w    -> List(N, Y, SrcType.ZERO, SrcType.IMM,  FuType.CSR,  CSROp.RDCNT_ID_W,      LA32RImmType.X,      Y),
rdcntvh_w  -> List(N, Y, SrcType.ZERO, SrcType.IMM,  FuType.CSR,  CSROp.RDCNT_VH_W,      LA32RImmType.X,      Y),
add_w      -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.ADD,             LA32RImmType.X,      N),
sub_w      -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.SUB,             LA32RImmType.X,      N),
slt        -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.LT,              LA32RImmType.X,      N),
sltu       -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.LTU,             LA32RImmType.X,      N),
nor        -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.NOR,             LA32RImmType.X,      N),
and        -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.AND,             LA32RImmType.X,      N),
or         -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.OR,              LA32RImmType.X,      N),
xor        -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.XOR,             LA32RImmType.X,      N),
sll_w      -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.LEFT,            LA32RImmType.X,      N),
srl_w      -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.RIGHT,           LA32RImmType.X,      N),
sra_w      -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.ARITH,           LA32RImmType.X,      N),
mul_w      -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.MUL,  MULOp.MUL,             LA32RImmType.X,      N),
mulh_w     -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.MUL,  MULOp.MULH,            LA32RImmType.X,      N),
mulh_wu    -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.MUL,  MULOp.MULHU,           LA32RImmType.X,      N),
div_w      -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.DIV,  DIVOp.DIV,             LA32RImmType.X,      N),
mod_w      -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.DIV,  DIVOp.REM,             LA32RImmType.X,      N),
div_wu     -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.DIV,  DIVOp.DIVU,            LA32RImmType.X,      N),
mod_wu     -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.DIV,  DIVOp.REMU,            LA32RImmType.X,      N),
break      -> List(N, N, SrcType.ZERO, SrcType.ZERO, FuType.FLAG, DecodeFlagOp.BRK,      LA32RImmType.X,      N),
syscall    -> List(N, N, SrcType.ZERO, SrcType.ZERO, FuType.FLAG, DecodeFlagOp.SYS,      LA32RImmType.X,      N),
slli_w     -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.LEFT,            LA32RImmType.UI5,    N),
srli_w     -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.RIGHT,           LA32RImmType.UI5,    N),
srai_w     -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.ARITH,           LA32RImmType.UI5,    N),
slti       -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.LT,              LA32RImmType.SI12,   N),
sltui      -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.LTU,             LA32RImmType.SI12,   N),
addi_w     -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.ADD,             LA32RImmType.SI12,   N),
andi       -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.AND,             LA32RImmType.UI12,   N),
ori        -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.OR,              LA32RImmType.UI12,   N),
xori       -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.XOR,             LA32RImmType.UI12,   N),
csrop      -> List(N, Y, SrcType.REG,  SrcType.ZERO, FuType.CSR,  CSROp.CSRXCHG,         LA32RImmType.CSR,    Y),
cacop      -> List(N, N, SrcType.REG,  SrcType.IMM,  FuType.FLAG, DecodeFlagOp.NONE,     LA32RImmType.SI12,   N), // TODO
tlbsrch    -> List(N, N, SrcType.ZERO, SrcType.ZERO, FuType.FLAG, DecodeFlagOp.TLBSRCH,  LA32RImmType.X,      Y), // TODO
tlbrd      -> List(N, N, SrcType.ZERO, SrcType.ZERO, FuType.FLAG, DecodeFlagOp.TLBRD,    LA32RImmType.X,      Y), // TODO
tlbwr      -> List(N, N, SrcType.ZERO, SrcType.ZERO, FuType.FLAG, DecodeFlagOp.TLBWR,    LA32RImmType.X,      Y), // TODO
tlbfill    -> List(N, N, SrcType.ZERO, SrcType.ZERO, FuType.FLAG, DecodeFlagOp.TLBFILL,  LA32RImmType.X,      Y), // TODO
ertn       -> List(N, N, SrcType.ZERO, SrcType.ZERO, FuType.FLAG, DecodeFlagOp.ERTN,     LA32RImmType.X,      Y),
idle       -> List(N, N, SrcType.ZERO, SrcType.ZERO, FuType.FLAG, DecodeFlagOp.WFI,      LA32RImmType.X,      N),
invtlb     -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.CSR,  CSROp.INVTLB,          LA32RImmType.INVTLB, Y), // TODO
lu12i_w    -> List(N, Y, SrcType.ZERO, SrcType.IMM,  FuType.ALU,  ALUOp.ADD,             LA32RImmType.SI20,   N),
pcaddu12i  -> List(N, Y, SrcType.PC,   SrcType.IMM,  FuType.BRU,  BRUOp.AUIPC,           LA32RImmType.SI20,   N),
ll_w       -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.AMO,  AMOOp.LR_W,            LA32RImmType.SI14,   Y),
sc_w       -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.AMO,  AMOOp.SC_W,            LA32RImmType.SI14,   Y),
ld_b       -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.LSU,  LSUOp.LB,              LA32RImmType.SI12,   N),
ld_h       -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.LSU,  LSUOp.LH,              LA32RImmType.SI12,   N),
ld_w       -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.LSU,  LSUOp.LW,              LA32RImmType.SI12,   N),
ld_bu      -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.LSU,  LSUOp.LBU,             LA32RImmType.SI12,   N),
ld_hu      -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.LSU,  LSUOp.LHU,             LA32RImmType.SI12,   N),
st_b       -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.LSU,  LSUOp.SB,              LA32RImmType.SI12,   N),
st_h       -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.LSU,  LSUOp.SH,              LA32RImmType.SI12,   N),
st_w       -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.LSU,  LSUOp.SW,              LA32RImmType.SI12,   N),
preld      -> List(N, N, SrcType.REG,  SrcType.IMM,  FuType.FLAG, DecodeFlagOp.NONE,     LA32RImmType.SI12,   N), // TODO
dbar       -> List(N, N, SrcType.ZERO, SrcType.ZERO, FuType.FLAG, DecodeFlagOp.FENCE,    LA32RImmType.X,      Y),
ibar       -> List(N, N, SrcType.ZERO, SrcType.ZERO, FuType.FLAG, DecodeFlagOp.FENCE_I,  LA32RImmType.X,      Y),
jirl       -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.BRU,  BRUOp.JALR,            LA32RImmType.OFFS16, N),
b          -> List(N, N, SrcType.PC,   SrcType.IMM,  FuType.BRU,  BRUOp.JAL,             LA32RImmType.OFFS26, N),
bl         -> List(N, Y, SrcType.PC,   SrcType.IMM,  FuType.BRU,  BRUOp.CALL,            LA32RImmType.OFFS26, N),
beq        -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.BRU,  BRUOp.BEQ,             LA32RImmType.OFFS16, N),
bne        -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.BRU,  BRUOp.BNE,             LA32RImmType.OFFS16, N),
blt        -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.BRU,  BRUOp.BLT,             LA32RImmType.OFFS16, N),
bge        -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.BRU,  BRUOp.BGE,             LA32RImmType.OFFS16, N),
bltu       -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.BRU,  BRUOp.BLTU,            LA32RImmType.OFFS16, N),
bgeu       -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.BRU,  BRUOp.BGEU,            LA32RImmType.OFFS16, N)
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

  val table = TruthTable(transformLUT(lut), listToBitPat(defaultCtrl))
  io.signals := decoder(io.inst, table).asTypeOf(new DecodeSignal)
}
// *
