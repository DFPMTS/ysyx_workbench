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

#include "debug.h"
#include "isa-def.h"
#include <isa.h>
#include <memory/paddr.h>

// since there is no unaligned access, vaddr is enough
// always translate

static word_t vaddr_op(vaddr_t addr, int len, word_t data, int mem_type) {
  paddr_t paddr = addr;
  bool translate_succ = true;
  if(isa_mmu_check(addr, len, mem_type)){
    if (isa_mmu_translate(addr, len, mem_type, &paddr)){
      // translate failed, exception generated
      translate_succ = false;
    }
  }

  // early return if exception
  if (!translate_succ) {
    return 0;
  }

  switch (mem_type)
  {
  case MEM_TYPE_READ:
    return paddr_read(paddr, len);
    break;

  case MEM_TYPE_IFETCH:
    return paddr_read(paddr, len);
    break;

  case MEM_TYPE_WRITE:
    paddr_write(paddr, len, data);
    return 0; // just dummy value here
    break;
  
  default:
    panic("Unknown MEM_TYPE: %d", mem_type);
    break;
  }
  panic("should not reach here");
  return 0;
}

inline bool addr_misalign(vaddr_t addr, int len) {
  return addr & (len - 1);
}

word_t vaddr_ifetch(vaddr_t addr, int len) {
  return vaddr_op(addr, len, 0, MEM_TYPE_IFETCH);
}

word_t vaddr_read(vaddr_t addr, int len) {
  if(addr_misalign(addr, len)){
    isa_set_trap(LOAD_ADDR_MISALIGNED, addr);
    return 0;
  }
  return vaddr_op(addr, len, 0, MEM_TYPE_READ);
}

void vaddr_write(vaddr_t addr, int len, word_t data) {
  if(addr_misalign(addr, len)){
    isa_set_trap(STORE_AMO_ADDR_MISALIGNED, addr);
    return;
  }
  vaddr_op(addr, len, data, MEM_TYPE_WRITE);
}
