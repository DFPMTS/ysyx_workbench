#ifndef UOP_HPP
#define UOP_HPP

#include "cpu.hpp"
#include <cstdint>

using CData = uint8_t;
using IData = uint32_t;

enum class SrcType : CData { ZERO = 0, REG = 1, IMM = 2, PC = 3 };

enum class FuType : CData {
  ALU = 0,
  BRU = 1,
  LSU = 2,
  MUL = 3,
  DIV = 4,
  AGU = 5,
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
  JALR = 0b1000,
  JAL = 0b1001,
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
  CSRR = 0b0000,
  CSRRW = 0b0001,
  CSRRS = 0b0010,
  CSRRC = 0b0011,
  CSRRWI = 0b0100,
  CSRRSI = 0b0101,
  CSRRCI = 0b0110,
  SRET = 0b1000,
  MRET = 0b1001,
  ECALL = 0b1010,
  EBREAK = 0b1011
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

enum class FlagOp : CData {
  NONE = 0,
  INST_ACCESS_FAULT = 1,
  ILLEGAL_INST = 2,
  BREAKPOINT = 3,
  LOAD_ADDR_MISALIGNED = 4,
  LOAD_ACCESS_FAULT = 5,
  STORE_ADDR_MISALIGNED = 6,
  STORE_ACCESS_FAULT = 7,
  DECODE_FLAG = 8,
  INTERRUPT = 9,
  /*
  10,
  11,
  */
  INST_PAGE_FAULT = 12,
  LOAD_PAGE_FAULT = 13,
  MISPREDICT = 14,
  STORE_PAGE_FAULT = 15
};

inline const char *getFlagOpName(FlagOp flag) {
  switch (flag) {
  case FlagOp::NONE:
    return "NONE";
  case FlagOp::INST_ACCESS_FAULT:
    return "INST_ACCESS_FAULT";
  case FlagOp::ILLEGAL_INST:
    return "ILLEGAL_INST";
  case FlagOp::BREAKPOINT:
    return "BREAKPOINT";
  case FlagOp::LOAD_ADDR_MISALIGNED:
    return "LOAD_ADDR_MISALIGNED";
  case FlagOp::LOAD_ACCESS_FAULT:
    return "LOAD_ACCESS_FAULT";
  case FlagOp::STORE_ADDR_MISALIGNED:
    return "STORE_ADDR_MISALIGNED";
  case FlagOp::STORE_ACCESS_FAULT:
    return "STORE_ACCESS_FAULT";
  case FlagOp::DECODE_FLAG:
    return "DECODE_FLAG";
  case FlagOp::INTERRUPT:
    return "INTERRUPT";
  case FlagOp::INST_PAGE_FAULT:
    return "INST_PAGE_FAULT";
  case FlagOp::LOAD_PAGE_FAULT:
    return "LOAD_PAGE_FAULT";
  case FlagOp::MISPREDICT:
    return "MISPREDICT";
  case FlagOp::STORE_PAGE_FAULT:
    return "STORE_PAGE_FAULT";
  default:
    return "UNKNOWN";
  }
}

enum class DecodeFlagOp : CData {
  ECALL = 0,
  EBREAK = 1,
  MRET = 2,
  SRET = 3,
  FENCE = 4,
  FENCE_I = 5,
  WFI = 6,
  SFENCE_VMA = 7,
  /*
  8-14
  */
  NONE = 15
};

inline const char *getDecodeFlagOpName(DecodeFlagOp flag) {
  switch (flag) {
  case DecodeFlagOp::ECALL:
    return "ECALL";
  case DecodeFlagOp::EBREAK:
    return "EBREAK";
  case DecodeFlagOp::MRET:
    return "MRET";
  case DecodeFlagOp::SRET:
    return "SRET";
  case DecodeFlagOp::FENCE:
    return "FENCE";
  case DecodeFlagOp::FENCE_I:
    return "FENCE_I";
  case DecodeFlagOp::WFI:
    return "WFI";
  case DecodeFlagOp::SFENCE_VMA:
    return "SFENCE_VMA";
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
  IData inst;

  CData rd;
  CData rs1;
  CData rs2;

  CData prd;
  CData prs1;
  CData prs2;

  IData src1;
  IData src2;
  IData imm;

  IData result;
  FlagOp flag;

  CData executed;
};

struct Uop {
  CData *valid;
  CData *ready = &True;
  bool isValid() { return *valid; }
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
  IData *ldqIndex;
  IData *stqIndex;
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
  IData *ldqIndex;
  IData *stqIndex;
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
  CData *delegate;
  CData *mret;
  CData *sret;
};

// * redirect
#define V_REDIRECT_VALID top->rootp->npc_top__DOT__npc__DOT__redirect_valid
#define V_REDIRECT_PC top->rootp->npc_top__DOT__npc__DOT__redirect_pc

// * rename
#define V_RENAME_UOP(i, field)                                                 \
  top->rootp->npc_top__DOT__npc__DOT__renameUop_##i##_##field

#define V_RENAME_VALID(i) top->rootp->npc_top__DOT__npc__DOT__renameRobValid_##i

#define V_RENAME_READY(i) top->rootp->npc_top__DOT__npc__DOT__renameRobReady

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
  X(i, ldqIndex)                                                               \
  X(i, stqIndex)                                                               \
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
  X(i, ldqIndex)                                                               \
  X(i, stqIndex)                                                               \
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
  X(i, delegate)                                                               \
  X(i, mret)                                                                   \
  X(i, sret)

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