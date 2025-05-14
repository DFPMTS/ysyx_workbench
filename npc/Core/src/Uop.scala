import chisel3._
import chisel3.util._
import utils._

object SrcType extends HasDecodeConfig {
  val ZERO = 0.U(2.W)
  val REG  = 1.U(2.W)
  val IMM  = 2.U(2.W)
  val PC   = 3.U(2.W)
}

object BrType extends HasDecodeConfig {
  val CALL = 0.U(2.W)
  val RET  = 1.U(2.W)
  val JUMP = 2.U(2.W)
  val BRANCH = 3.U(2.W)
  
  def apply() = UInt(2.W)
}

object PredecBrType extends HasDecodeConfig {
  val CALL   = 0.U(3.W)
  val ICALL  = 1.U(3.W)

  val RET    = 3.U(3.W)
  val JUMP   = 4.U(3.W)
  val IJUMP  = 5.U(3.W)
  val BRANCH = 6.U(3.W)

  def isIndirect (code: UInt) = {
    code(0)
  }

  def isDirectJump (code: UInt) = {
    isJump(code) && !isIndirect(code)
  }

  def isJump (code: UInt) = {
    code === JUMP || code === IJUMP || code === CALL || code === ICALL
  }

  def isBranch (code: UInt) = {
    code === BRANCH
  }


  def apply() = UInt(3.W)
}

class PTE extends CoreBundle {
  val ppn1 = UInt(10.W)
  val ppn0 = UInt(10.W)
  val rsw = UInt(2.W)
  val d = Bool()
  val a = Bool()
  val g = Bool()
  val u = Bool()
  val x = Bool()
  val w = Bool()
  val r = Bool()
  val v = Bool()
}

object PTE {
  def apply() = new PTE
}

// TODO : add C extension
// * "With the addition of the C extension, no instructions can 
// *  raise instruction-address-misaligned exceptions."
// * So we are safe to use its encoding space for NONE exception.
object FlagOp extends HasDecodeConfig {
  val NONE                  = 0.U(FlagWidth.W)
  val INST_ACCESS_FAULT     = 1.U(FlagWidth.W)
  val ILLEGAL_INST          = 2.U(FlagWidth.W)
  // val BREAKPOINT            = 3.U(FlagWidth.W)
  
  val LOAD_ADDR_MISALIGNED  = 4.U(FlagWidth.W)
  val LOAD_ACCESS_FAULT     = 5.U(FlagWidth.W)
  val STORE_ADDR_MISALIGNED = 6.U(FlagWidth.W)
  val STORE_ACCESS_FAULT    = 7.U(FlagWidth.W)
  // * custom begin
  val DECODE_FLAG           = 8.U(FlagWidth.W) // * rd field stores DecodeFlagOp
  // val INTERRUPT             = 9.U(FlagWidth.W)
  val BRANCH_TAKEN          = 9.U(FlagWidth.W)
  val BRANCH_NOT_TAKEN      = 10.U(FlagWidth.W)
  val MISPREDICT_TAKEN      = 11.U(FlagWidth.W) // ! Temp
  // val INST_PAGE_FAULT       = 12.U(FlagWidth.W)
  val MISPREDICT_NOT_TAKEN  = 12.U(FlagWidth.W) // ! Temp
  // * custom end
  val LOAD_PAGE_FAULT       = 13.U(FlagWidth.W)
  // * temporary jump
  val MISPREDICT_JUMP       = 14.U(FlagWidth.W) // ! Temp
  val STORE_PAGE_FAULT      = 15.U(FlagWidth.W)

  val isBranchTaken = (flag: UInt) => {
    flag === BRANCH_TAKEN || flag === MISPREDICT_TAKEN
  }

  val isBranchNotTaken = (flag: UInt) => {
    flag === BRANCH_NOT_TAKEN || flag === MISPREDICT_NOT_TAKEN
  }

  val isRedirect = (flag: UInt) => {
    flag === MISPREDICT_TAKEN || flag === MISPREDICT_NOT_TAKEN ||
    flag === MISPREDICT_JUMP 
  }

  val isNoRedirect = (flag: UInt) => {
    flag === BRANCH_TAKEN || flag === BRANCH_NOT_TAKEN
  }

  val isBruFlags = (flag: UInt) => {
    flag === BRANCH_TAKEN || flag === BRANCH_NOT_TAKEN ||
    flag === MISPREDICT_TAKEN || flag === MISPREDICT_NOT_TAKEN ||
    flag === MISPREDICT_JUMP
  }
}

object Exception extends HasDecodeConfig {
  val INST_ADDR_MISALIGNED  = 0.U(FlagWidth.W)
  val INST_ACCESS_FAULT     = 1.U(FlagWidth.W)
  val ILLEGAL_INST          = 2.U(FlagWidth.W)
  val BREAKPOINT            = 3.U(FlagWidth.W)
  val LOAD_ADDR_MISALIGNED  = 4.U(FlagWidth.W)
  val LOAD_ACCESS_FAULT     = 5.U(FlagWidth.W)
  val STORE_ADDR_MISALIGNED = 6.U(FlagWidth.W)
  val STORE_ACCESS_FAULT    = 7.U(FlagWidth.W)
  val ECALL_FROM_U          = 8.U(FlagWidth.W)
  val ECALL_FROM_S          = 9.U(FlagWidth.W)
  val RESERVED_0            = 10.U(FlagWidth.W)
  val ECALL_FROM_M          = 11.U(FlagWidth.W)
  val INST_PAGE_FAULT       = 12.U(FlagWidth.W)
  val LOAD_PAGE_FAULT       = 13.U(FlagWidth.W)
  val RESERVED_1            = 14.U(FlagWidth.W)
  val STORE_PAGE_FAULT      = 15.U(FlagWidth.W)
}

object DecodeFlagOp extends HasDecodeConfig {
  val ECALL      = 0.U(FlagWidth.W)
  val EBREAK     = 1.U(FlagWidth.W)
  val MRET       = 2.U(FlagWidth.W)
  val SRET       = 3.U(FlagWidth.W)
  val FENCE      = 4.U(FlagWidth.W)
  val FENCE_I    = 5.U(FlagWidth.W)
  val WFI        = 6.U(FlagWidth.W)
  val SFENCE_VMA = 7.U(FlagWidth.W)
  val INTERRUPT  = 8.U(FlagWidth.W)
  val INST_PAGE_FAULT = 9.U(FlagWidth.W)


  val NONE    = 15.U(FlagWidth.W)
}

object Dest extends HasDecodeConfig {
  val ROB = 0.U(1.W) // goes to Rob
  val PTW = 1.U(1.W) // goes to Page Table Walker
}

object FuType extends HasDecodeConfig {
  val ALU    = 0.U(FuTypeWidth.W)
  val BRU    = 1.U(FuTypeWidth.W)
  val LSU    = 2.U(FuTypeWidth.W)
  val MUL    = 3.U(FuTypeWidth.W)
  val DIV    = 4.U(FuTypeWidth.W)
  val AMO    = 5.U(FuTypeWidth.W)
  val CSR    = 6.U(FuTypeWidth.W)
  val FLAG   = 7.U(FuTypeWidth.W)

  def isOneCycle (fuType: UInt) = {
    fuType === ALU || fuType === BRU
  }
}

object ALUOp extends HasDecodeConfig {
  def ADD = "b0000".U(OpcodeWidth.W)  

  def LEFT  = "b0010".U(OpcodeWidth.W)
  def RIGHT = "b0011".U(OpcodeWidth.W)
  def AND   = "b0100".U(OpcodeWidth.W)
  def OR    = "b0101".U(OpcodeWidth.W)
  def XOR   = "b0110".U(OpcodeWidth.W)
  def ARITH = "b0111".U(OpcodeWidth.W)

  def SUB = "b1000".U(OpcodeWidth.W)
  // "b1001"
  def EQ = "b1010".U(OpcodeWidth.W)
  def NE = "b1011".U(OpcodeWidth.W)

  def LT  = "b1100".U(OpcodeWidth.W)
  def LTU = "b1101".U(OpcodeWidth.W)

  def GE  = "b1110".U(OpcodeWidth.W)
  def GEU = "b1111".U(OpcodeWidth.W)

  def isSub (opcode: UInt) = {
    opcode(3).asBool
  }
}

object BRUOp extends HasDecodeConfig {
  def AUIPC  = "b0000".U(OpcodeWidth.W)

  def CALL   = "b0001".U(OpcodeWidth.W)
  def RET    = "b0010".U(OpcodeWidth.W)

  def JALR   = "b0011".U(OpcodeWidth.W)
  def JAL    = "b0100".U(OpcodeWidth.W)

  // * Below use Sub
  def BEQ = "b1010".U(OpcodeWidth.W)
  def BNE = "b1011".U(OpcodeWidth.W)
  def BLT  = "b1100".U(OpcodeWidth.W)
  def BLTU = "b1101".U(OpcodeWidth.W)
  def BGE  = "b1110".U(OpcodeWidth.W)
  def BGEU = "b1111".U(OpcodeWidth.W)

  def isJump(opcode: UInt) = {
    !opcode(3) && (opcode =/= AUIPC)
  }

  def isBranch (opcode: UInt) = {
    opcode(3)
  }

  def toBrType(opcode: UInt) = {
    MuxLookup(opcode, BrType.BRANCH)(Seq(
      CALL -> BrType.CALL,
      RET  -> BrType.RET,
      JALR -> BrType.JUMP,
      JAL  -> BrType.JUMP,
      BEQ  -> BrType.BRANCH,
      BNE  -> BrType.BRANCH,
      BLT  -> BrType.BRANCH,
      BLTU -> BrType.BRANCH,
      BGE  -> BrType.BRANCH,
      BGEU -> BrType.BRANCH
    ))
  }
}

object LSUOp extends HasDecodeConfig {
  def LB  = "b0000".U(OpcodeWidth.W)
  def LBU = "b0001".U(OpcodeWidth.W)
  def LH  = "b0010".U(OpcodeWidth.W)
  def LHU = "b0011".U(OpcodeWidth.W)
  def LW  = "b0100".U(OpcodeWidth.W)    
  def SB  = "b1000".U(OpcodeWidth.W)
  def SH  = "b1010".U(OpcodeWidth.W)
  def SW  = "b1100".U(OpcodeWidth.W)

  def isLoad(opcode: UInt) = opcode(3) === 0.U
  def isStore(opcode: UInt) = opcode(3) === 1.U
}

object AMOOp extends HasDecodeConfig {
  def LR_W   = "b0000".U(OpcodeWidth.W)
  def SC_W   = "b0001".U(OpcodeWidth.W)
  def SWAP_W = "b0010".U(OpcodeWidth.W)
  def ADD_W  = "b0011".U(OpcodeWidth.W)
  def XOR_W  = "b0100".U(OpcodeWidth.W)
  def AND_W  = "b0101".U(OpcodeWidth.W)
  def OR_W   = "b0110".U(OpcodeWidth.W)
  def MIN_W  = "b0111".U(OpcodeWidth.W)
  def MAX_W  = "b1000".U(OpcodeWidth.W)
  def MINU_W = "b1001".U(OpcodeWidth.W)
  def MAXU_W = "b1010".U(OpcodeWidth.W)
}

object CSROp extends HasDecodeConfig {
  def CSRR  = "b0000".U(OpcodeWidth.W)
  def CSRRW = "b0001".U(OpcodeWidth.W)
  def CSRRS = "b0010".U(OpcodeWidth.W)
  def CSRRC = "b0011".U(OpcodeWidth.W)

  def CSRRWI = "b0100".U(OpcodeWidth.W)
  def CSRRSI = "b0101".U(OpcodeWidth.W)
  def CSRRCI = "b0110".U(OpcodeWidth.W)
}

object MULOp extends HasDecodeConfig {
  def MUL    = 0.U(OpcodeWidth.W)
  def MULH   = 1.U(OpcodeWidth.W)
  def MULHSU = 2.U(OpcodeWidth.W)
  def MULHU  = 3.U(OpcodeWidth.W) 
}

object DIVOp extends HasDecodeConfig {
  def DIV  = 0.U(OpcodeWidth.W)
  def DIVU = 1.U(OpcodeWidth.W)
  def REM  = 2.U(OpcodeWidth.W)
  def REMU = 3.U(OpcodeWidth.W)
}

object ImmType extends HasDecodeConfig{
  def I = 0.U(ImmTypeWidth.W)
  def U = 1.U(ImmTypeWidth.W)
  def S = 2.U(ImmTypeWidth.W)
  def B = 3.U(ImmTypeWidth.W)
  def J = 4.U(ImmTypeWidth.W)
  def X = BitPat.dontCare(ImmTypeWidth)
}

object CImmType extends HasDecodeConfig {
  object CI {
    def LWSP = 0.U(ImmTypeWidth.W)
    def ADDI = 1.U(ImmTypeWidth.W)
    def SLLI = 2.U(ImmTypeWidth.W)
    def SLTI = 3.U(ImmTypeWidth.W)
    def ADDI16SP = 4.U(ImmTypeWidth.W)
    def LUI = 5.U(ImmTypeWidth.W)
  }
  object CSS {
    def SW = 6.U(ImmTypeWidth.W)
  }
  def CIW = 7.U(ImmTypeWidth.W)
  object CL {
    def LW = 8.U(ImmTypeWidth.W)
  }
  object CS {
    def SW = 9.U(ImmTypeWidth.W)
  }
  def CA = 10.U(ImmTypeWidth.W)
  object CB {
    def BRANCH = 11.U(ImmTypeWidth.W)
    def SHIFT = 12.U(ImmTypeWidth.W)
    def ANDI = 13.U(ImmTypeWidth.W)
  }
  def CJ = 14.U(ImmTypeWidth.W)
  def X = BitPat.dontCare(ImmTypeWidth)
}

class DecodeUop extends CoreBundle{  
  val rd = UInt(5.W)
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)  

  val src1Type = UInt(2.W)
  val src2Type = UInt(2.W)

  val imm = UInt(32.W)
  val pc = UInt(XLEN.W)

  val fuType = UInt(FuTypeWidth.W)
  val opcode = UInt(OpcodeWidth.W)

  val predTarget = UInt(XLEN.W)
  val compressed = Bool()

  val lockBackend = Bool()

  val phtState = new SaturatedCounter
  val lastBranch  = Bool()

  // * debug
  val inst = UInt(32.W)
}

class RenameUop extends CoreBundle {
  val  rd = UInt(5.W)
  val prd = UInt(PREG_IDX_W)

  val prs1 = UInt(PREG_IDX_W)
  val prs2 = UInt(PREG_IDX_W)

  val src1Type = UInt(2.W)
  val src2Type = UInt(2.W)

  val src1Ready = Bool()
  val src2Ready = Bool()

  val robPtr = RingBufferPtr(ROB_SIZE)
  val ldqPtr = RingBufferPtr(LDQ_SIZE)
  val stqPtr = RingBufferPtr(STQ_SIZE)

  val imm =  UInt(32.W)  
  val pc = UInt(XLEN.W)
  
  val fuType = UInt(FuTypeWidth.W)
  val opcode = UInt(OpcodeWidth.W)

  val lockBackend = Bool()

  val predTarget = UInt(XLEN.W)
  val compressed = Bool()

  val phtState = new SaturatedCounter
  val lastBranch  = Bool()

  // * debug
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val inst = UInt(32.W)
}

class ReadRegUop extends CoreBundle {
  val rd   = UInt(5.W)
  val prd  = UInt(PREG_IDX_W)

  val prs1 = UInt(PREG_IDX_W)
  val prs2 = UInt(PREG_IDX_W)

  val src1 = UInt(XLEN.W)
  val src2 = UInt(XLEN.W)

  val robPtr = RingBufferPtr(ROB_SIZE)
  val ldqPtr = RingBufferPtr(LDQ_SIZE)
  val stqPtr = RingBufferPtr(STQ_SIZE)

  val imm = UInt(32.W)
  val pc = UInt(XLEN.W)

  val fuType = UInt(FuTypeWidth.W)
  val opcode = UInt(OpcodeWidth.W)

  val predTarget = UInt(XLEN.W)
  val compressed = Bool()
}

class FlagUop extends CoreBundle {
  val rd   = UInt(PREG_IDX_W)
  val flag = UInt(FLAG_W)
  val pc   = UInt(XLEN.W)
  val target = UInt(XLEN.W)
  // * debug
  val robPtr = RingBufferPtr(ROB_SIZE)
}

class WritebackUop extends CoreBundle {
  val prd = UInt(PREG_IDX_W)
  val data = UInt(XLEN.W)
  val dest = UInt(1.W)
  val robPtr = RingBufferPtr(ROB_SIZE)
  val flag = UInt(FLAG_W)
  // * temporary
  val target = UInt(XLEN.W)
}

class CommitUop extends CoreBundle {
  val rd = UInt(5.W)
  val prd = UInt(PREG_IDX_W)
  val robPtr = RingBufferPtr(ROB_SIZE)
  val pc = UInt(XLEN.W)
  val flag = UInt(FLAG_W)
  val target = UInt(XLEN.W)
  val ldqPtr = RingBufferPtr(LDQ_SIZE)
  val stqPtr = RingBufferPtr(STQ_SIZE)
  val result = UInt(XLEN.W)
}

class RedirectUop extends CoreBundle {
  val target = UInt(XLEN.W)
  val robPtr = RingBufferPtr(ROB_SIZE)
  val ldqPtr = RingBufferPtr(LDQ_SIZE)
  val stqPtr = RingBufferPtr(STQ_SIZE)
  val flush = Bool()
}

object CacheOpcode extends HasDecodeConfig {
  val LOAD       = 0.U(4.W) // cache[index0][assoc_id0] <- mem[addr0]
  val REPLACE    = 1.U(4.W) // mem[addr0] <- cache[index0][assoc_id0], cache[index1][assoc_id1] <- mem[addr1]
  val INVALIDATE = 2.U(4.W) // mem[addr0] <- cache[index0][assoc_id0]

  val UNCACHED_LB = 8.U(4.W)
  val UNCACHED_LH = 9.U(4.W)
  val UNCACHED_LW = 10.U(4.W)
  val UNCACHED_SB = 12.U(4.W)
  val UNCACHED_SH = 13.U(4.W)
  val UNCACHED_SW = 14.U(4.W)

  def isUnCached(opcode: UInt) = {
    opcode(3)
  }
  def isUnCachedLoad(opcode: UInt) = {
    isUnCached(opcode) && !opcode(2)
  }
  def isUnCachedStore(opcode: UInt) = {
    isUnCached(opcode) && opcode(2)
  }
}

object CacheId extends HasCoreParameters {
  val ICACHE = 0.U(CACHE_ID_LEN.W)
  val DCACHE = 1.U(CACHE_ID_LEN.W)
}

class CacheCtrlUop extends CoreBundle {
  val index = UInt(log2Up(DCACHE_SETS).W)
  val rtag = UInt(DCACHE_TAG.W)
  val way = UInt(log2Up(DCACHE_WAYS).W)
  val wtag = UInt(DCACHE_TAG.W)
  val wmask = UInt(4.W)
  val offset = UInt(log2Up(CACHE_LINE_B).W)
  val wdata = UInt(32.W)
  val opcode = UInt(4.W)
  val cacheId = UInt(CACHE_ID_LEN.W)
  
  def loadAddrAlreadyInFlight(cacheId: UInt, addr: UInt) = {
    (opcode === CacheOpcode.LOAD || opcode === CacheOpcode.REPLACE) &&
    this.cacheId === cacheId &&
    addr(XLEN - 1, log2Up(CACHE_LINE_B)) === Cat(rtag, index)
  }

  def readAddr() = {
    Cat(rtag, index, offset)
  }

  def writeAddr() = {
    Cat(wtag, index, 0.U(log2Up(CACHE_LINE_B).W))
  }

  def cacheLocation() = {
    Cat(way, index)
  }
}

object Addr {
  def isUncached(addr: UInt) = {
    addr < "h1000_0000".U || addr >= "h2000_0000".U // ! Temporary Fix For Speculative Load
  }
  def isInternalMMIO(addr: UInt) = {
    addr >= "h0b00_0000".U && addr < "h0c00_0000".U
  }
  def isMainMem(addr: UInt) = {
    addr >= "h1000_0000".U && addr < "h2000_0000".U
  }
}