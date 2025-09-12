/***************************************************************************************
* Copyright (c) 2014-2022 Zihao Yu, Nanjing University
*
* NEMU is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

#ifndef __ISA_RISCV_H__
#define __ISA_RISCV_H__

#include <common.h>

enum {
  PRIV_U,
  PRIV_S,
  PRIV_H,
  PRIV_M
};

typedef union {
  struct {
    uint32_t _WPRI_0 : 1;
    uint32_t SIE : 1;
    uint32_t _WPRI_1 : 1;
    uint32_t MIE : 1;
    uint32_t _WPRI_2 : 1;
    uint32_t SPIE : 1;
    uint32_t UBE : 1;
    uint32_t MPIE : 1;
    uint32_t SPP : 1;
    uint32_t VS : 2;
    uint32_t MPP : 2;
    uint32_t FS : 2;
    uint32_t XS : 2;
    uint32_t MPRV : 1;
    uint32_t SUM : 1;
    uint32_t MXR : 1;
    uint32_t TVM : 1;
    uint32_t TW : 1;
    uint32_t TSR : 1;
    uint32_t _WPRI_3 : 8;
    uint32_t SD : 1;
  };
  uint32_t val;
} Mstatus;

typedef union {
  struct {
    uint32_t _ZERO_0 : 1;
    uint32_t SSI : 1;
    uint32_t _ZERO_1 : 3;
    uint32_t STI : 1;
    uint32_t _ZERO_2 : 1;
    uint32_t MTI : 1;
    uint32_t _ZERO_3 : 24;
  };
  uint32_t val;
} Mipe;

typedef struct {
  word_t gpr[MUXDEF(CONFIG_RVE, 16, 32)];
  vaddr_t pc;
  Mstatus mstatus;
  word_t mcause;
  word_t mepc;
  word_t priv;
  word_t stval;
  word_t sepc;
  word_t scause;
  
  word_t mtval;
  word_t medeleg;
  word_t mideleg;

  word_t misa;
  word_t mhartid;
  word_t mstatush;
  word_t mscratch;
  word_t mtvec;
  word_t menvcfg;
  word_t menvcfgh;

  word_t stvec;
  word_t satp;  
  word_t sscratch;    
  Mipe mip;
  Mipe mie;

  // helper fields  
  word_t trap;
  word_t xtval;
  word_t reservation;
} MUXDEF(CONFIG_RV64, riscv64_CPU_state, riscv32_CPU_state);

typedef enum {
  INST_ADDR_MISALIGNED,
  INST_ACCESS_FAULT,
  ILLEGAL_INST,
  BREAKPOINT,
  LOAD_ADDR_MISALIGNED,
  LOAD_ACCESS_FAULT,
  STORE_AMO_ADDR_MISALIGNED,
  STORE_AMO_ACCESS_FAULT,
  ECALL_FROM_U,
  ECALL_FROM_S,
  RESERVED_0,
  ECALL_FROM_M,
  INST_PAGE_FAULT,
  LOAD_PAGE_FAULT,
  RESERVED_1,
  STORE_AMO_PAGE_FAULT,
  DOUBLE_TRAP,
  RESERVED_2,
  SOFTWARE_CHECK,
  HARDWARE_ERROR,
} EXCEPTION_NO;

typedef enum {
  CSRRW,
  CSRRS,
  CSRRC,
  CSRRWI,
  CSRRSI,
  CSRRCI
} CSR_OP;

word_t csrr_(word_t csr, uint32_t rd, uint32_t rs1, word_t value, CSR_OP op);

// decode
typedef struct {
  union {
    uint32_t val;
  } inst;
} MUXDEF(CONFIG_RV64, riscv64_ISADecodeInfo, riscv32_ISADecodeInfo);

#define isa_mmu_check(vaddr, len, type)                                        \
  (((cpu.satp >> 31) & 1) &&                                                   \
   (cpu.priv < PRIV_M || (cpu.mstatus.MPRV == 1 && cpu.mstatus.MPP < PRIV_M && type != MEM_TYPE_IFETCH)))

#define isRVC(inst)  ((inst & 3) != 3)

#define MASK(a, b) ((1ull << (a + 1)) - (1ull << b))

#define CSR_MSTATUS_WMASK   (    CSR_SSTATUS_WMASK   | /* TSR,TW,TVM */ MASK(22, 20) | /* MPRV */ MASK(17, 17)\
                            | /* MPP */ MASK(12, 11) |    /* MPIE */    MASK(7, 7)   | /* MIE */  MASK(3, 3))

#define CSR_MENVCFG_WMASK   ( /* FIOM */ MASK(0, 0))

#define CSR_MENVCFGH_WMASK   ( 0 )

#define CSR_SSTATUS_WMASK   ( /* MXR */ MASK(19, 19) | /* SUM */ MASK(18, 18) | /* FS */ MASK(14, 13)\
                            | /* SPP */ MASK(8, 8)   | /* SPIE */ MASK(5, 5)  | /* SIE */ MASK(1, 1) )
// #define CSR_SIE_WMASK       
// #define CSR_SIP_WMASK       

#define CSR_SSTATUS_RMASK   ( CSR_SSTATUS_WMASK |  /* SD */ MASK(31, 31) | /* XS */ MASK(16, 15) )
// #define CSR_SIE_RMASK       0x2222
// #define CSR_SIP_RMASK       0x2222



#define CSR_SSTATUS         0x100
#define CSR_SIE             0x104
#define CSR_STVEC           0x105
#define CSR_SCOUNTEREN      0x106
#define CSR_SSCRATCH        0x140
#define CSR_SEPC            0x141
#define CSR_SCAUSE          0x142
#define CSR_STVAL           0x143
#define CSR_SIP             0x144
#define CSR_SATP            0x180

#define CSR_MSTATUS         0x300
#define CSR_MISA            0x301
#define CSR_MEDELEG         0x302
#define CSR_MIDELEG         0x303
#define CSR_MIE             0x304
#define CSR_MTVEC           0x305
#define CSR_MCOUNTEREN      0x306
#define CSR_MENVCFG         0x30A
#define CSR_MSTATUSH        0x310
#define CSR_MENVCFGH        0x31A
#define CSR_MSCRATCH        0x340
#define CSR_MEPC            0x341
#define CSR_MCAUSE          0x342
#define CSR_MTVAL           0x343
#define CSR_MIP             0x344

#define CSR_MVENDORID       0xF11
#define CSR_MARCHID         0xF12
#define CSR_MIMPID          0xF13
#define CSR_MHARTID         0xF14

#define CSR_TIME            0xC01
#define CSR_TIMEH           0xC81

#endif
