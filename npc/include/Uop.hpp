#ifndef UOP_HPP
#define UOP_HPP

#include "cpu.hpp"
#include <cstdint>

using CData = uint8_t;
using IData = uint32_t;

enum class SrcType : CData { ZERO = 0, REG = 1, IMM = 2, PC = 3 };

enum class Dest : CData { ROB = 0, PTW = 1 };

enum class FuType : CData {
  ALU = 0,
  BRU = 1,
  LSU = 2,
  MUL = 3,
  DIV = 4,
  AMO = 5,
  CSR = 6,
  FLAG = 7
};

enum class ALUOp : CData {
  ADD = 0b0000,
  SUB = 0b0001,
  LEFT = 0b0010,
  RIGHT = 0b0011,
  AND = 0b0100,
  OR = 0b0101,
  XOR = 0b0110,
  ARITH = 0b0111,
  EQ = 0b1010,
  NE = 0b1011,
  LT = 0b1100,
  LTU = 0b1101,
  GE = 0b1110,
  GEU = 0b1111
};

enum class BRUOp : CData {
  AUIPC = 0b0000,

  CALL = 0b0001,
  RET = 0b0010,

  JALR = 0b0011,
  JAL = 0b0100,

  BEQ = 0b1010,
  BNE = 0b1011,
  BLT = 0b1100,
  BLTU = 0b1101,
  BGE = 0b1110,
  BGEU = 0b1111
};

enum class LSUOp : CData {
  LB = 0b0000,
  LH = 0b0001,
  LW = 0b0010,
  LBU = 0b0100,
  LHU = 0b0101,
  SB = 0b1000,
  SH = 0b1001,
  SW = 0b1010
};

enum class CSROp : CData {
  CSRRD = 0b0000,
  CSRWR = 0b0001,
  CSRXCHG = 0b0010,

  RDCNT_ID_W = 0b0111,
  RDCNT_VL_W = 0b1000,
  RDCNT_VH_W = 0b1001,

  CPUCFG = 0b1010,

  INVTLB = 0b1111
};

enum class ImmType : CData { I = 0, U = 1, S = 2, B = 3, J = 4, X = 0xFF };

enum class CImmType : CData {
  LWSP = 0,
  ADDI = 1,
  SLLI = 2,
  SLTI = 3,
  ADDI16SP = 4,
  LUI = 5,
  CSS_SW = 6,
  CIW = 7,
  LW = 8,
  CS_SW = 9,
  CA = 10,
  BRANCH = 11,
  SHIFT = 12,
  ANDI = 13,
  CJ = 14,
  X = 0xFF
};

/*
val NONE                  = 0x0.U(FlagWidth.W)
  val PIL                   = 0x1.U(FlagWidth.W)
  val PIS                   = 0x2.U(FlagWidth.W)
  val PIF                   = 0x3.U(FlagWidth.W)
  val PME                   = 0x4.U(FlagWidth.W)
  // 0x5
  // 0x6
  val PPI                   = 0x7.U(FlagWidth.W)
  val ADEF                  = 0x8.U(FlagWidth.W)
  val ALE                   = 0x9.U(FlagWidth.W)
  val DECODE_FLAG           = 0xA.U(FlagWidth.W) // * rd field stores
DecodeFlagOp val SYS                   = 0xB.U(FlagWidth.W) val BRK =
0xC.U(FlagWidth.W) val INE                   = 0xD.U(FlagWidth.W) val IPE =
0xE.U(FlagWidth.W) val TLBR                  = 0xF.U(FlagWidth.W)

  // * custom begin
  val BRANCH_TAKEN          = 0x10.U(FlagWidth.W)
  val BRANCH_NOT_TAKEN      = 0x11.U(FlagWidth.W)
  val MISPREDICT_TAKEN      = 0x12.U(FlagWidth.W)
  val MISPREDICT_NOT_TAKEN  = 0x13.U(FlagWidth.W)
  val MISPREDICT_JUMP       = 0x14.U(FlagWidth.W)
*/

enum class FlagOp : CData {
  NONE = 0x0,
  PIL = 0x1,
  PIS = 0x2,
  PIF = 0x3,
  PME = 0x4,
  // 0x5
  // 0x6
  PPI = 0x7,
  ADEF = 0x8,
  ALE = 0x9,
  DECODE_FLAG = 0xA,
  SYS = 0xB,
  BRK = 0xC,
  INE = 0xD,
  IPE = 0xE,
  TLBR = 0xF,
  // * custom begin
  BRANCH_TAKEN = 0x10,
  BRANCH_NOT_TAKEN = 0x11,
  MISPREDICT_TAKEN = 0x12,
  MISPREDICT_NOT_TAKEN = 0x13,
  MISPREDICT_JUMP = 0x14,
};

inline const char *getFlagOpName(FlagOp flag) {
  switch (flag) {
  case FlagOp::NONE:
    return "NONE";
  case FlagOp::PIL:
    return "PAGE ILLEGAL LOAD";
  case FlagOp::PIS:
    return "PAGE ILLEGAL STORE";
  // case FlagOp::BREAKPOINT:
  //   return "BREAKPOINT";
  case FlagOp::PIF:
    return "PAGE ILLEGAL FETCH";
  case FlagOp::PME:
    return "PAGE MODIFY EXCEPTION";
  case FlagOp::PPI:
    return "PAGE PRIV EXCEPTION";
  case FlagOp::ADEF:
    return "ADDRESS DECODE EXCEPTION FETCH";
  case FlagOp::DECODE_FLAG:
    return "DECODE_FLAG";
  case FlagOp::SYS:
    return "SYSTEM CALL";
  case FlagOp::BRK:
    return "BREAK";
  case FlagOp::INE:
    return "INST NOT EXIST";
  case FlagOp::IPE:
    return "INST PRIV EXCEPTION";
  case FlagOp::TLBR:
    return "TLB REFILL";
  case FlagOp::BRANCH_TAKEN:
    return "BRANCH_TAKEN";
  case FlagOp::BRANCH_NOT_TAKEN:
    return "BRANCH_NOT_TAKEN";
  case FlagOp::MISPREDICT_TAKEN:
    return "MISPREDICT_TAKEN";
  case FlagOp::MISPREDICT_NOT_TAKEN:
    return "MISPREDICT_NOT_TAKEN";
  case FlagOp::MISPREDICT_JUMP:
    return "MISPREDICT_JUMP";
  default:
    return "UNKNOWN";
  }
}

enum class DecodeFlagOp : CData {
  SYS = 0,
  BRK = 1,
  ERTN = 2,
  INTERRUPT = 3,
  FENCE = 4,
  FENCE_I = 5,
  WFI = 6,
  SFENCE_VMA = 7,
  TLBSRCH = 8,
  TLBRD = 9,
  TLBWR = 10,
  TLBFILL = 11,
  FETCH_PPI = 12,  // * fetch PPI
  FETCH_TLBR = 13, // * fetch TLBR
  // 14
  NONE = 15
};

inline const char *getDecodeFlagOpName(DecodeFlagOp flag) {
  switch (flag) {
  case DecodeFlagOp::SYS:
    return "SYS";
  case DecodeFlagOp::BRK:
    return "BRK";
  case DecodeFlagOp::ERTN:
    return "ERTN";
  case DecodeFlagOp::INTERRUPT:
    return "INTERRUPT";
  case DecodeFlagOp::FENCE:
    return "FENCE";
  case DecodeFlagOp::FENCE_I:
    return "FENCE_I";
  case DecodeFlagOp::WFI:
    return "WFI";
  case DecodeFlagOp::SFENCE_VMA:
    return "SFENCE_VMA";
  case DecodeFlagOp::TLBSRCH:
    return "TLBSRCH";
  case DecodeFlagOp::TLBRD:
    return "TLBRD";
  case DecodeFlagOp::TLBWR:
    return "TLBWR";
  case DecodeFlagOp::TLBFILL:
    return "TLBFILL";
  case DecodeFlagOp::FETCH_PPI:
    return "FETCH_PPI";
  case DecodeFlagOp::FETCH_TLBR:
    return "FETCH_TLBR";
  case DecodeFlagOp::NONE:
    return "NONE";
  default:
    return "UNKNOWN";
  }
}

/*
class CSRCtrl extends CoreBundle {
  // * trap
  val trap  = Bool()
  val intr  = Bool()
  val pc    = UInt(XLEN.W)
  val cause = UInt(4.W)
  val delegate = Bool()
  // * mret/sret
  val mret  = Bool()
  val sret  = Bool()
}

*/

inline CData True = 1;

struct InstInfo {
  CData valid;

  FuType fuType;
  CData opcode;

  IData pc;
  IData predTarget;
  IData inst;

  CData rd;
  CData rs1;
  CData rs2;

  CData prd;
  CData prs1;
  CData prs2;

  CData ldqPtr_flag;
  CData ldqPtr_index;
  CData stqPtr_flag;
  CData stqPtr_index;

  CData robPtr_index;

  IData src1;
  IData src2;
  IData imm;

  IData result;
  uint64_t resultValidCycle;
  FlagOp flag;

  IData paddr;

  IData target;
  CData executed;

  uint64_t konataId;

  uint32_t iqNumber;
};

struct Uop {
  CData *valid;
  CData *ready = &True;
  bool isValid() { return *valid; }
  bool isFire() { return *valid && *ready; }
};

struct DecodeUop : Uop {
  CData *rd;
  CData *rs1;
  CData *rs2;

  CData *src1Type;
  CData *src2Type;

  CData *imm;
  IData *pc;

  FuType *fuType;
  CData *opcode;

  IData *predTarget;
  CData *compressed;

  CData *lockBackend;

  IData *inst;
};

struct RenameUop : Uop {
  CData *rd;
  CData *prd;
  CData *prs1;
  CData *prs2;
  SrcType *src1Type;
  SrcType *src2Type;
  CData *src1Ready;
  CData *src2Ready;
  CData *robPtr_flag;
  CData *robPtr_index;
  CData *ldqPtr_flag;
  CData *ldqPtr_index;
  CData *stqPtr_flag;
  CData *stqPtr_index;
  IData *imm;
  IData *pc;
  FuType *fuType;
  CData *opcode;
  IData *predTarget;
  CData *compressed;

  IData *inst;
  CData *rs1;
  CData *rs2;
};

struct ReadRegUop : Uop {
  CData *rd;
  CData *prd;
  CData *prs1;
  CData *prs2;
  IData *src1;
  IData *src2;
  CData *robPtr_flag;
  CData *robPtr_index;
  CData *ldqPtr_flag;
  CData *ldqPtr_index;
  CData *stqPtr_flag;
  CData *stqPtr_index;
  IData *imm;
  IData *pc;
  FuType *fuType;
  CData *opcode;
  IData *predTarget;
  CData *compressed;
};

struct WritebackUop : Uop {
  CData *prd;
  IData *data;
  CData *dest;
  CData *robPtr_flag;
  CData *robPtr_index;
  FlagOp *flag;
  IData *target; // temporary
};

struct CommitUop : Uop {
  CData *rd;
  CData *prd;
  CData *robPtr_flag;
  CData *robPtr_index;
};

struct FlagUop : Uop {
  CData *rd;
  CData *flag;
  IData *pc;
  IData *target;
  CData *robPtr_flag;
  CData *robPtr_index;
};

struct CSRCtrl : Uop {
  CData *trap;
  CData *intr;
  IData *pc;
  CData *cause;
  CData *ertn;
};

struct AGUUop : Uop {
  IData *addr;
  CData *robPtr_flag;
  CData *robPtr_index;
  CData *ldqPtr_flag;
  CData *ldqPtr_index;
  CData *stqPtr_flag;
  CData *stqPtr_index;
};

struct Ptr {

  uint8_t m_size;

  Ptr(uint8_t size, uint8_t flag, uint8_t index)
      : m_size(size), m_flag(flag), m_index(index) {}

  uint8_t m_flag;
  uint8_t m_index;

  void inc() {
    m_index++;
    if (m_index >= m_size) {
      m_index = 0;
      m_flag = !m_flag;
    }
  }

  void reset(uint8_t flag) {
    m_flag = flag;
    m_index = 0;
  }
};

// * redirect
#define V_REDIRECT_VALID top->rootp->npc_top__DOT__npc__DOT__redirect_valid
#define V_REDIRECT_PC top->rootp->npc_top__DOT__npc__DOT__redirect_pc

// * decode
#define V_DECODE_UOP(i, field)                                                 \
  top->rootp->npc_top__DOT__npc__DOT__decodeUop_##i##_bits_##field

#define V_DECODE_VALID(i)                                                      \
  top->rootp->npc_top__DOT__npc__DOT__decodeUop_##i##_valid
#define V_DECODE_READY(i)                                                      \
  top->rootp->npc_top__DOT__npc__DOT__decodeUop_##i##_ready

// * rename
#define V_RENAME_UOP(i, field)                                                 \
  top->rootp->npc_top__DOT__npc__DOT__renameUop_##i##_##field

#define V_RENAME_ROB_VALID(i)                                                  \
  top->rootp->npc_top__DOT__npc__DOT__renameRobValid_##i

#define V_RENAME_IQ_VALID(i)                                                   \
  top->rootp->npc_top__DOT__npc__DOT__renameIQValid_##i

#define V_RENAME_IQ_READY(i)                                                   \
  top->rootp->npc_top__DOT__npc__DOT__renameIQReady_##i

// * issue
#define V_ISSUE_UOP(i, field)                                                  \
  top->rootp->npc_top__DOT__npc__DOT__issueUop_##i##_bits_##field
#define V_ISSUE_VALID(i)                                                       \
  top->rootp->npc_top__DOT__npc__DOT__issueUop_##i##_valid
#define V_ISSUE_READY(i)                                                       \
  top->rootp->npc_top__DOT__npc__DOT__issueUop_##i##_ready

// * writeback
#define V_WRITEBACK_UOP(i, field)                                              \
  top->rootp->npc_top__DOT__npc__DOT__writebackUop_##i##_bits_##field

#define V_WRITEBACK_VALID(i)                                                   \
  top->rootp->npc_top__DOT__npc__DOT__writebackUop_##i##_valid

// * commit
#define V_COMMIT_UOP(i, field)                                                 \
  top->rootp->npc_top__DOT__npc__DOT__commitUop_##i##_bits_##field

#define V_COMMIT_VALID(i)                                                      \
  top->rootp->npc_top__DOT__npc__DOT__commitUop_##i##_valid

// * read register
#define V_READREG_UOP(i, field)                                                \
  top->rootp->npc_top__DOT__npc__DOT__readRegUop_##i##_bits_##field

#define V_READREG_VALID(i)                                                     \
  top->rootp->npc_top__DOT__npc__DOT__readRegUop_##i##_valid

#define V_READREG_READY(i)                                                     \
  top->rootp->npc_top__DOT__npc__DOT__readRegUop_##i##_ready

// * flag
#define V_FLAG_UOP(i, field)                                                   \
  top->rootp->npc_top__DOT__npc__DOT__flagUop_bits_##field
#define V_FLAG_VALID(i) top->rootp->npc_top__DOT__npc__DOT__flagUop_valid

// * CSRCtrl
#define V_CSR_CTRL(i, field) top->rootp->npc_top__DOT__npc__DOT__CSRCtrl_##field

// * AGUUop
#define V_AGU_UOP(i, field)                                                    \
  top->rootp->npc_top__DOT__npc__DOT__aguUop_bits_##field
#define V_AGU_VALID(i) top->rootp->npc_top__DOT__npc__DOT__aguUop_valid

#define DECODE_FIELDS(X, i)                                                    \
  X(i, rd)                                                                     \
  X(i, rs1)                                                                    \
  X(i, rs2)                                                                    \
  X(i, src1Type)                                                               \
  X(i, src2Type)                                                               \
  X(i, imm)                                                                    \
  X(i, pc)                                                                     \
  X(i, fuType)                                                                 \
  X(i, opcode)                                                                 \
  X(i, predTarget)                                                             \
  X(i, compressed)                                                             \
  X(i, inst)

#define RENAME_FIELDS(X, i)                                                    \
  X(i, rd)                                                                     \
  X(i, prd)                                                                    \
  X(i, prs1)                                                                   \
  X(i, prs2)                                                                   \
  X(i, src1Type)                                                               \
  X(i, src2Type)                                                               \
  X(i, src1Ready)                                                              \
  X(i, src2Ready)                                                              \
  X(i, robPtr_flag)                                                            \
  X(i, robPtr_index)                                                           \
  X(i, ldqPtr_flag)                                                            \
  X(i, ldqPtr_index)                                                           \
  X(i, stqPtr_flag)                                                            \
  X(i, stqPtr_index)                                                           \
  X(i, imm)                                                                    \
  X(i, pc)                                                                     \
  X(i, fuType)                                                                 \
  X(i, opcode)                                                                 \
  X(i, predTarget)                                                             \
  X(i, compressed)                                                             \
  X(i, inst)                                                                   \
  X(i, rs1)                                                                    \
  X(i, rs2)

#define WRITEBACK_FIELDS(X, i)                                                 \
  X(i, prd)                                                                    \
  X(i, data)                                                                   \
  X(i, dest)                                                                   \
  X(i, robPtr_flag)                                                            \
  X(i, robPtr_index)                                                           \
  X(i, flag)                                                                   \
  X(i, target)

#define COMMIT_FIELDS(X, i)                                                    \
  X(i, rd)                                                                     \
  X(i, prd)                                                                    \
  X(i, robPtr_flag)                                                            \
  X(i, robPtr_index)

#define READREG_FIELDS(X, i)                                                   \
  X(i, rd)                                                                     \
  X(i, prd)                                                                    \
  X(i, prs1)                                                                   \
  X(i, prs2)                                                                   \
  X(i, src1)                                                                   \
  X(i, src2)                                                                   \
  X(i, robPtr_flag)                                                            \
  X(i, robPtr_index)                                                           \
  X(i, ldqPtr_flag)                                                            \
  X(i, ldqPtr_index)                                                           \
  X(i, stqPtr_flag)                                                            \
  X(i, stqPtr_index)                                                           \
  X(i, imm)                                                                    \
  X(i, pc)                                                                     \
  X(i, fuType)                                                                 \
  X(i, opcode)                                                                 \
  X(i, predTarget)                                                             \
  X(i, compressed)

#define FLAG_FIELDS(X, i)                                                      \
  X(i, rd)                                                                     \
  X(i, flag)                                                                   \
  X(i, pc)                                                                     \
  X(i, target)                                                                 \
  X(i, robPtr_flag)                                                            \
  X(i, robPtr_index)

#define CSR_CTRL_FIELDS(X, i)                                                  \
  X(i, trap)                                                                   \
  X(i, intr)                                                                   \
  X(i, pc)                                                                     \
  X(i, cause)                                                                  \
  X(i, ertn)

#define AGU_FIELDS(X, i)                                                       \
  X(i, addr)                                                                   \
  X(i, robPtr_flag)                                                            \
  X(i, robPtr_index)                                                           \
  X(i, ldqPtr_flag)                                                            \
  X(i, ldqPtr_index)                                                           \
  X(i, stqPtr_flag)                                                            \
  X(i, stqPtr_index)

#define BIND_ONE_FIELD(i, field)                                               \
  UOP[i].field = (decltype(UOP[i].field))&V_UOP(i, field);

#define BIND_VALID(i) UOP[i].valid = (decltype(UOP[i].valid))&V_UOP_VALID(i);

#define BIND_READY(i) UOP[i].ready = (decltype(UOP[i].ready))&V_UOP_READY(i);

#define BIND_FIELDS(i) UOP_FIELDS(BIND_ONE_FIELD, i)

#define REPEAT_1(FN) FN(0)
#define REPEAT_2(FN) REPEAT_1(FN) FN(1)
#define REPEAT_3(FN) REPEAT_2(FN) FN(2)
#define REPEAT_4(FN) REPEAT_3(FN) FN(3)
#define REPEAT_5(FN) REPEAT_4(FN) FN(4)
#define REPEAT_6(FN) REPEAT_5(FN) FN(5)
#define REPEAT_7(FN) REPEAT_6(FN) FN(6)

#endif