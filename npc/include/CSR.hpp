#ifndef CSR_HPP
#define CSR_HPP

#include "cpu.hpp"

using IData = uint32_t;
/*

  object CSRList {
    val sstatus        = 0x100.U
    val sie            = 0x104.U
    val stvec          = 0x105.U
    val scounteren     = 0x106.U

    val sscratch       = 0x140.U
    val sepc           = 0x141.U
    val scause         = 0x142.U
    val stval          = 0x143.U
    val sip            = 0x144.U
    val satp           = 0x180.U

    val mstatus        = 0x300.U
    val misa           = 0x301.U
    val medeleg        = 0x302.U
    val mideleg        = 0x303.U
    val mie            = 0x304.U
    val mtvec          = 0x305.U
    val mcounteren     = 0x306.U
    val menvcfg        = 0x30A.U
    val mstatush       = 0x310.U
    val menvcfgh       = 0x31A.U
    val mscratch       = 0x340.U
    val mepc           = 0x341.U
    val mcause         = 0x342.U
    val mtval          = 0x343.U
    val mip            = 0x344.U

    val time           = 0xC01.U
    val timeh          = 0xC81.U

    val mvendorid      = 0xF11.U
    val marchid        = 0xF12.U
    val mipid          = 0xF13.U
    val mhartid        = 0xF14.U
  }
*/

class CSR {
public:
  IData *stvec;
  IData *sscratch;
  IData *sepc;
  IData *scause;
  IData *stval;
  IData *satp;
  IData *mstatus;
  IData *medeleg;
  IData *mideleg;
  IData *mie;
  IData *mtvec;
  IData *menvcfg;
  IData *mscratch;
  IData *mepc;
  IData *mcause;
  IData *mtval;
  IData *mip;
};

#define CSR_FIELDS(X)                                                          \
  X(stvec, )                                                                   \
  X(sscratch, )                                                                \
  X(sepc, )                                                                    \
  X(scause, )                                                                  \
  X(stval, )                                                                   \
  X(satp, )                                                                    \
  X(mstatus, U)                                                                \
  X(medeleg, )                                                                 \
  X(mideleg, )                                                                 \
  X(mie, U)                                                                    \
  X(mtvec, )                                                                   \
  X(menvcfg, U)                                                                \
  X(mscratch, )                                                                \
  X(mepc, )                                                                    \
  X(mcause, )                                                                  \
  X(mtval, )                                                                   \
  X(mip, U)

#define V_CSRS(field, suffix)                                                  \
  top->rootp->npc_top__DOT__npc__DOT__csr__DOT__##field##suffix

#define BIND_CSRS_FIELD(field, suffix)                                         \
  CSRS.field = (decltype(CSRS.field))&V_CSRS(field, suffix);

#endif