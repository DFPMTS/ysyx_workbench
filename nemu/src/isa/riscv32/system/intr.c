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

#include "isa-def.h"
#include <isa.h>

bool trap_is_intr(word_t NO) { return NO & (1u << 31); }
word_t trap_exception_code(word_t NO) { return NO & ~(1u << 31); }

#define BIT(X, I) (((X) >> (I)) & 1)

word_t isa_raise_intr(word_t NO, vaddr_t epc) {
  /* TODO: Trigger an interrupt/exception with ``NO''.
   * Then return the address of the interrupt/exception vector.
   */  
  assert(NO);  
  // if(!trap_is_intr(NO)){
  //   if(NO == 2) {
  //     Log("priv: %d NO: 0x%x epc: 0x%x mtvec: 0x%x",cpu.priv, NO, epc, cpu.mtvec);
  //   }
  // }
  //// always trap to M for now
  // check delegation
  bool delegate = false;
  // never delegate to lower priv
  if(cpu.priv < PRIV_M){
    // clear NO highest bit
    word_t exception_code = trap_exception_code(NO);
    bool is_intr = trap_is_intr(NO);
    // Log("is_intr: %d  exception_code: %d", is_intr, exception_code);
    if (is_intr) {
      delegate = BIT(cpu.mideleg, exception_code);
    } else {
      delegate = BIT(cpu.medeleg, exception_code);
    }
  }

  word_t next_pc = 0;

  if (delegate) {
    // Log("delegate");
    // trap to S
    cpu.mstatus.SPIE = cpu.mstatus.SIE;
    cpu.mstatus.SIE = 0;

    cpu.mstatus.SPP = cpu.priv;

    cpu.sepc = epc;
    cpu.scause = NO;
    cpu.stval = cpu.xtval;
    cpu.priv = PRIV_S;

    next_pc = cpu.stvec;
  } else {
    // trap to M
    cpu.mstatus.MPIE = cpu.mstatus.MIE;
    cpu.mstatus.MIE = 0;

    cpu.mstatus.MPP = cpu.priv;

    cpu.mepc = epc;
    cpu.mcause = NO;
    cpu.mtval = cpu.xtval;
    cpu.priv = PRIV_M;

    next_pc = cpu.mtvec;
  }

  // clear trap
  cpu.trap = INTR_EMPTY;

  return next_pc;
}

uint64_t read_mtime();
uint64_t read_mtimecmp();

word_t isa_query_intr() {
#ifdef CONFIG_TARGET_SHARE
  return INTR_EMPTY;
#endif
  // set mip/mie
  if (read_mtime() >= read_mtimecmp()) {
    cpu.mip.MTI = 1;
  } else {
    cpu.mip.MTI = 0;
  }
  
  // trap to M
  if(cpu.priv < PRIV_M || cpu.mstatus.MIE){
    if (((Mipe)(cpu.mie.val & cpu.mip.val)).MTI && !BIT(cpu.mideleg, 7)) {
      // machine timer interrupt
      return INTR_MTI;
    }
    if (((Mipe)(cpu.mie.val & cpu.mip.val)).STI && !BIT(cpu.mideleg, 5)) {
      // supervisor timer interrupt
      return INTR_STI;
    }
    if (((Mipe)(cpu.mie.val & cpu.mip.val)).SSI && !BIT(cpu.mideleg, 1)) {
      // supervisor software interrupt
      return INTR_SSI;
    }
  }

  // trap to S
  if(cpu.priv < PRIV_S || (cpu.priv == PRIV_S && cpu.mstatus.SIE)){
    if (((Mipe)(cpu.mie.val & cpu.mip.val)).STI && BIT(cpu.mideleg, 5)) {
      // supervisor timer interrupt
      return INTR_STI;
    }
    if (((Mipe)(cpu.mie.val & cpu.mip.val)).SSI && BIT(cpu.mideleg, 1)) {
      // supervisor software interrupt
      return INTR_SSI;
    }
  }

  return INTR_EMPTY;
}

word_t isa_mret() {
  // * An MRET or SRET instruction that changes the privilege mode
  // * to a mode less privileged than M also sets MPRV=0.
  if (cpu.mstatus.MPP < PRIV_M) {
    cpu.mstatus.MPRV = 0;
  }
  cpu.priv = cpu.mstatus.MPP;
  cpu.mstatus.MIE = cpu.mstatus.MPIE;
  cpu.mstatus.MPIE = 1;
  cpu.mstatus.MPP = PRIV_U;
  return cpu.mepc;
}

word_t isa_sret() {
  // * An MRET or SRET instruction that changes the privilege mode
  // * to a mode less privileged than M also sets MPRV=0.
  if (cpu.mstatus.SPP < PRIV_M) {
    cpu.mstatus.MPRV = 0;
  }
  cpu.priv = cpu.mstatus.SPP;
  cpu.mstatus.SIE = cpu.mstatus.SPIE;
  cpu.mstatus.SPIE = 1;
  cpu.mstatus.SPP = PRIV_U;
  return cpu.sepc;
}


void isa_set_trap(EXCEPTION_NO NO, word_t xtval) {
  cpu.trap = NO;
  cpu.xtval = xtval;
}