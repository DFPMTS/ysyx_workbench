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

#include <isa.h>
#include <stdio.h>
#include "local-include/reg.h"
#include "cpu/decode.h"

const char *regs[] = {
  "$0", "ra", "sp", "gp", "tp", "t0", "t1", "t2",
  "s0", "s1", "a0", "a1", "a2", "a3", "a4", "a5",
  "a6", "a7", "s2", "s3", "s4", "s5", "s6", "s7",
  "s8", "s9", "s10", "s11", "t3", "t4", "t5", "t6"
};

static void __attribute__((unused)) print_csr(const char*name, word_t val)
{
  printf("%s\t", name);
  for (int i = sizeof(word_t); i; --i) {
    for (int j = i * 8 - 1; j >= (i - 1) * 8; --j) {
      printf("%d", (val >> j) & 1);
    }
    printf(" ");
  }
  printf("\n");
}

uint64_t read_mtime();
uint64_t read_mtimecmp();

void isa_reg_display() {
  printf("============================================\n");  
  printf("pc\t%08X\n", cpu.pc);
  for (int i = 0; i < MUXDEF(CONFIG_RVE, 16, 32); ++i) {
    printf("%s\t%08X\t%d\n",reg_name(i),gpr(i),gpr(i));
  }
  printf("priv:\t%d\n", cpu.priv);
  printf("stvec: %08X\n", cpu.stvec);
  printf("sscratch: %08X\n", cpu.sscratch);
  printf("sepc: %08X\n", cpu.sepc);
  printf("scause: %08X\n", cpu.scause);
  printf("stval: %08X\n", cpu.stval);
  printf("satp: %08X\n", cpu.satp);
  printf("mstatus: %08X\n", cpu.mstatus.val);
  printf("medeleg: %08X\n", cpu.medeleg);
  printf("mideleg: %08X\n", cpu.mideleg);
  printf("mie: %08X\n", cpu.mie.val);
  printf("mtvec: %08X\n", cpu.mtvec);
  printf("menvcfg: %08X\n", cpu.menvcfg);
  printf("mscratch: %08X\n", cpu.mscratch);
  printf("mepc: %08X\n", cpu.mepc);
  printf("mcause: %08X\n", cpu.mcause);
  printf("mtval: %08X\n", cpu.mtval);
  printf("mip: %08X\n", cpu.mip.val);
  printf("time: %016lX\n", read_mtime());
  printf("mtimecmp: %016lX\n", read_mtimecmp());
  printf("============================================\n");
}

word_t isa_reg_str2val(const char *s, bool *success) {
  // gpr
  for (int i = 0; i < ARRLEN(regs); ++i) {
    if (strcmp(s, regs[i]) == 0) {
      return gpr(i);
    }
  }
  // pc
  if( strcmp(s, "pc") == 0){
    return cpu.pc;
  }
  *success = false;
  return -1;
}
