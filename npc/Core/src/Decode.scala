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

class Decode extends CoreModule {
  val io = IO(new Bundle {
    val inst    = Input(UInt(32.W))
    val signals = Output(new DecodeSignal)
  })
  implicit def uintToBitPat(x: UInt): BitPat = BitPat(x)
  def lui        = BitPat("b??????? ????? ????? ??? ????? 01101 11")
  def auipc      = BitPat("b??????? ????? ????? ??? ????? 00101 11")
  def jal        = BitPat("b??????? ????? ????? ??? ????? 11011 11")
  def jalr       = BitPat("b??????? ????? ????? ??? ????? 11001 11")
  def beq        = BitPat("b??????? ????? ????? 000 ????? 11000 11")
  def bne        = BitPat("b??????? ????? ????? 001 ????? 11000 11")
  def blt        = BitPat("b??????? ????? ????? 100 ????? 11000 11")
  def bge        = BitPat("b??????? ????? ????? 101 ????? 11000 11")
  def bltu       = BitPat("b??????? ????? ????? 110 ????? 11000 11")
  def bgeu       = BitPat("b??????? ????? ????? 111 ????? 11000 11")
  def lb         = BitPat("b??????? ????? ????? 000 ????? 00000 11")
  def lh         = BitPat("b??????? ????? ????? 001 ????? 00000 11")
  def lw         = BitPat("b??????? ????? ????? 010 ????? 00000 11")
  def lbu        = BitPat("b??????? ????? ????? 100 ????? 00000 11")
  def lhu        = BitPat("b??????? ????? ????? 101 ????? 00000 11")
  def sb         = BitPat("b??????? ????? ????? 000 ????? 01000 11")
  def sh         = BitPat("b??????? ????? ????? 001 ????? 01000 11")
  def sw         = BitPat("b??????? ????? ????? 010 ????? 01000 11")
  def addi       = BitPat("b??????? ????? ????? 000 ????? 00100 11")
  def slti       = BitPat("b??????? ????? ????? 010 ????? 00100 11")
  def sltiu      = BitPat("b??????? ????? ????? 011 ????? 00100 11")
  def xori       = BitPat("b??????? ????? ????? 100 ????? 00100 11")
  def ori        = BitPat("b??????? ????? ????? 110 ????? 00100 11")
  def andi       = BitPat("b??????? ????? ????? 111 ????? 00100 11")
  def slli       = BitPat("b0000000 ????? ????? 001 ????? 00100 11")
  def srli       = BitPat("b0000000 ????? ????? 101 ????? 00100 11")
  def srai       = BitPat("b0100000 ????? ????? 101 ????? 00100 11")
  def add        = BitPat("b0000000 ????? ????? 000 ????? 01100 11")
  def sub        = BitPat("b0100000 ????? ????? 000 ????? 01100 11")
  def sll        = BitPat("b0000000 ????? ????? 001 ????? 01100 11")
  def slt        = BitPat("b0000000 ????? ????? 010 ????? 01100 11")
  def sltu       = BitPat("b0000000 ????? ????? 011 ????? 01100 11")
  def xor        = BitPat("b0000000 ????? ????? 100 ????? 01100 11")
  def srl        = BitPat("b0000000 ????? ????? 101 ????? 01100 11")
  def sra        = BitPat("b0100000 ????? ????? 101 ????? 01100 11")
  def or         = BitPat("b0000000 ????? ????? 110 ????? 01100 11")
  def and        = BitPat("b0000000 ????? ????? 111 ????? 01100 11")
  def fence_i    = BitPat("b??????? ????? ????? 001 ????? 00011 11")
  def ecall      = BitPat("b0000000 00000 00000 000 00000 11100 11")
  def ebreak     = BitPat("b0000000 00001 00000 000 00000 11100 11")
  def csrrw      = BitPat("b??????? ????? ????? 001 ????? 11100 11")
  def csrrs      = BitPat("b??????? ????? ????? 010 ????? 11100 11")
  def csrrc      = BitPat("b??????? ????? ????? 011 ????? 11100 11")
  def csrrwi     = BitPat("b??????? ????? ????? 101 ????? 11100 11")
  def csrrsi     = BitPat("b??????? ????? ????? 110 ????? 11100 11")
  def csrrci     = BitPat("b??????? ????? ????? 111 ????? 11100 11")
  def mret       = BitPat("b0011000 00010 00000 000 00000 11100 11")
  def sret       = BitPat("b0001000 00010 00000 000 00000 11100 11")
  def fence      = BitPat("b???? ???? ???? ????? 000 ????? 00011 11")
  def sfence_vma = BitPat("b0001001 ????? ????? 000 00000 11100 11")
  def wfi        = BitPat("b0001000 00101 00000 000 00000 11100 11")
  // * RV32M
  def mul        = BitPat("b0000001 ????? ????? 000 ????? 01100 11")
  def mulh       = BitPat("b0000001 ????? ????? 001 ????? 01100 11")
  def mulhsu     = BitPat("b0000001 ????? ????? 010 ????? 01100 11")
  def mulhu      = BitPat("b0000001 ????? ????? 011 ????? 01100 11")
  def div        = BitPat("b0000001 ????? ????? 100 ????? 01100 11")
  def divu       = BitPat("b0000001 ????? ????? 101 ????? 01100 11")
  def rem        = BitPat("b0000001 ????? ????? 110 ????? 01100 11")
  def remu       = BitPat("b0000001 ????? ????? 111 ????? 01100 11")
  // * RV32A
  def lr_w       = BitPat("b00010?? 00000 ????? 010 ????? 0101111")
  def sc_w       = BitPat("b00011?? ????? ????? 010 ????? 0101111")
  def amoswap_w  = BitPat("b00001?? ????? ????? 010 ????? 0101111")
  def amoadd_w   = BitPat("b00000?? ????? ????? 010 ????? 0101111")
  def amoxor_w   = BitPat("b00100?? ????? ????? 010 ????? 0101111")
  def amoand_w   = BitPat("b01100?? ????? ????? 010 ????? 0101111")
  def amoor_w    = BitPat("b01000?? ????? ????? 010 ????? 0101111")
  def amomin_w   = BitPat("b10000?? ????? ????? 010 ????? 0101111")
  def amomax_w   = BitPat("b10100?? ????? ????? 010 ????? 0101111")
  def amominu_w  = BitPat("b11000?? ????? ????? 010 ????? 0101111")
  def amomaxu_w  = BitPat("b11100?? ????? ????? 010 ????? 0101111")
  


  val defaultCtrl: List[BitPat] = List(Y, N, BitPat.dontCare(2), BitPat.dontCare(2), BitPat.dontCare(FuTypeWidth), BitPat.dontCare(OpcodeWidth), ImmType.X, N)
  val lut: List[(BitPat, List[BitPat])] = List(
lui        -> List(N, Y, SrcType.ZERO, SrcType.IMM,  FuType.ALU,  ALUOp.ADD,                  ImmType.U, N),
auipc      -> List(N, Y, SrcType.PC,   SrcType.IMM,  FuType.ALU,  ALUOp.ADD,                  ImmType.U, N),
jal        -> List(N, Y, SrcType.PC,   SrcType.IMM,  FuType.BRU,  BRUOp.JAL,                  ImmType.J, N),
jalr       -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.BRU,  BRUOp.JALR,                 ImmType.I, N),
beq        -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.BRU,  BRUOp.BEQ,                  ImmType.B, N),
bne        -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.BRU,  BRUOp.BNE,                  ImmType.B, N),
blt        -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.BRU,  BRUOp.BLT,                  ImmType.B, N),
bge        -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.BRU,  BRUOp.BGE,                  ImmType.B, N),
bltu       -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.BRU,  BRUOp.BLTU,                 ImmType.B, N),
bgeu       -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.BRU,  BRUOp.BGEU,                 ImmType.B, N),
lb         -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.LSU,  LSUOp.LB,                   ImmType.I, N),
lh         -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.LSU,  LSUOp.LH,                   ImmType.I, N),
lw         -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.LSU,  LSUOp.LW,                   ImmType.I, N),
lbu        -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.LSU,  LSUOp.LBU,                  ImmType.I, N),
lhu        -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.LSU,  LSUOp.LHU,                  ImmType.I, N),
sb         -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.LSU,  LSUOp.SB,                   ImmType.S, N),
sh         -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.LSU,  LSUOp.SH,                   ImmType.S, N),
sw         -> List(N, N, SrcType.REG,  SrcType.REG,  FuType.LSU,  LSUOp.SW,                   ImmType.S, N),
addi       -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.ADD,                  ImmType.I, N),
slti       -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.LT,                   ImmType.I, N),
sltiu      -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.LTU,                  ImmType.I, N),
xori       -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.XOR,                  ImmType.I, N),
ori        -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.OR,                   ImmType.I, N),
andi       -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.AND,                  ImmType.I, N),
slli       -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.LEFT,                 ImmType.I, N),
srli       -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.RIGHT,                ImmType.I, N),
srai       -> List(N, Y, SrcType.REG,  SrcType.IMM,  FuType.ALU,  ALUOp.ARITH,                ImmType.I, N),
add        -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.ADD,                  ImmType.X, N),
sub        -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.SUB,                  ImmType.X, N),
sll        -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.LEFT,                 ImmType.X, N),
slt        -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.LT,                   ImmType.X, N),
sltu       -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.LTU,                  ImmType.X, N),
xor        -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.XOR,                  ImmType.X, N),
srl        -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.RIGHT,                ImmType.X, N),
sra        -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.ARITH,                ImmType.X, N),
or         -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.OR,                   ImmType.X, N),
and        -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.ALU,  ALUOp.AND,                  ImmType.X, N),
fence      -> List(N, N, SrcType.ZERO, SrcType.ZERO, FuType.FLAG, DecodeFlagOp.NONE,          ImmType.X, N), // TODO
fence_i    -> List(N, N, SrcType.ZERO, SrcType.ZERO, FuType.FLAG, DecodeFlagOp.FENCE_I,       ImmType.X, Y), // TODO
sfence_vma -> List(N, N, SrcType.ZERO, SrcType.ZERO, FuType.FLAG, DecodeFlagOp.SFENCE_VMA,    ImmType.X, Y),
ecall      -> List(N, N, SrcType.ZERO, SrcType.ZERO, FuType.FLAG, DecodeFlagOp.ECALL,         ImmType.X, N),
ebreak     -> List(N, N, SrcType.ZERO, SrcType.ZERO, FuType.FLAG, DecodeFlagOp.EBREAK,        ImmType.X, N),
csrrw      -> List(N, Y, SrcType.REG,  SrcType.ZERO, FuType.CSR,  CSROp.CSRRW,                ImmType.I, N),
csrrs      -> List(N, Y, SrcType.REG,  SrcType.ZERO, FuType.CSR,  CSROp.CSRRS,                ImmType.I, N),
csrrc      -> List(N, Y, SrcType.REG,  SrcType.ZERO, FuType.CSR,  CSROp.CSRRC,                ImmType.I, N),
csrrwi     -> List(N, Y, SrcType.REG,  SrcType.ZERO, FuType.CSR,  CSROp.CSRRWI,               ImmType.I, N),
csrrsi     -> List(N, Y, SrcType.REG,  SrcType.ZERO, FuType.CSR,  CSROp.CSRRSI,               ImmType.I, N),
csrrci     -> List(N, Y, SrcType.REG,  SrcType.ZERO, FuType.CSR,  CSROp.CSRRCI,               ImmType.I, N),
mret       -> List(N, N, SrcType.ZERO, SrcType.ZERO, FuType.FLAG, DecodeFlagOp.MRET,          ImmType.X, N),
sret       -> List(N, N, SrcType.ZERO, SrcType.ZERO, FuType.FLAG, DecodeFlagOp.SRET,          ImmType.X, N),
wfi        -> List(N, N, SrcType.ZERO, SrcType.ZERO, FuType.FLAG, DecodeFlagOp.NONE,          ImmType.X, N), // TODO
mul        -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.MUL,  MULOp.MUL,                  ImmType.X, N),// * RV32M begin
mulh       -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.MUL,  MULOp.MULH,                 ImmType.X, N),
mulhsu     -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.MUL,  MULOp.MULHSU,               ImmType.X, N),
mulhu      -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.MUL,  MULOp.MULHU,                ImmType.X, N),
div        -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.DIV,  DIVOp.DIV,                  ImmType.X, N),
divu       -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.DIV,  DIVOp.DIVU,                 ImmType.X, N),
rem        -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.DIV,  DIVOp.REM,                  ImmType.X, N),
remu       -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.DIV,  DIVOp.REMU,                 ImmType.X, N),
lr_w       -> List(N, Y, SrcType.REG,  SrcType.ZERO, FuType.AMO,  AMOOp.LR_W,                 ImmType.X, Y),// * RV32A begin
sc_w       -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.AMO,  AMOOp.SC_W,                 ImmType.X, Y),
amoswap_w  -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.AMO,  AMOOp.SWAP_W,               ImmType.X, Y),
amoadd_w   -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.AMO,  AMOOp.ADD_W,                ImmType.X, Y),
amoxor_w   -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.AMO,  AMOOp.XOR_W,                ImmType.X, Y),
amoand_w   -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.AMO,  AMOOp.AND_W,                ImmType.X, Y),
amoor_w    -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.AMO,  AMOOp.OR_W,                 ImmType.X, Y),
amomin_w   -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.AMO,  AMOOp.MIN_W,                ImmType.X, Y),
amomax_w   -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.AMO,  AMOOp.MAX_W,                ImmType.X, Y),
amominu_w  -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.AMO,  AMOOp.MINU_W,               ImmType.X, Y),
amomaxu_w  -> List(N, Y, SrcType.REG,  SrcType.REG,  FuType.AMO,  AMOOp.MAXU_W,               ImmType.X, Y),


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
