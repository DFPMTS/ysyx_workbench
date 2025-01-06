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

static void print_csr(const char*name, word_t val)
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

void isa_reg_display() {
  printf("============================================\n");
  printf("priv:\t%d\n", cpu.priv);
  printf("pc\t%08X\n", cpu.pc);
  for (int i = 0; i < MUXDEF(CONFIG_RVE, 16, 32); ++i) {
    printf("%s\t%08X\t%d\n",reg_name(i),gpr(i),gpr(i));
  }
  print_csr("mstatus", cpu.mstatus.val);
  printf("mtvec\t%x\n", cpu.mtvec);
  printf("mtval\t%x\n", cpu.mtval);
  printf("mepc\t%x\n", cpu.mepc);
  printf("mscratch\t%x\n", cpu.mscratch);
  printf("satp\t%x\n", cpu.satp);
  printf("stvec\t%x\n", cpu.stvec);  
  printf("stval\t%x\n", cpu.stval);
  printf("sepc\t%x\n", cpu.sepc);  
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
