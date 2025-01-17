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
#include <isa.h>
#include <memory/vaddr.h>
#include <memory/paddr.h>

// create a mask of length LEN
#define MASK_LEN(LEN) ((1 << (LEN)) - 1)

enum {
  PTE_V,
  PTE_R,
  PTE_W,
  PTE_X,
  PTE_U,
  PTE_G,
  PTE_A,
  PTE_D
};

// get the bit N of PTE
#define BIT(PTE, N) (((PTE) >> (N)) & 1)

static void set_translate_exception(int type, int vaddr)
{
  switch (type) {
  case MEM_TYPE_READ:
    isa_set_trap(LOAD_PAGE_FAULT, vaddr);
    break;
  case MEM_TYPE_IFETCH:
    isa_set_trap(INST_PAGE_FAULT, vaddr);
    break;
  case MEM_TYPE_WRITE:
    isa_set_trap(STORE_AMO_PAGE_FAULT, vaddr);
    break;
  default:
    panic("Unknown MEM_TYPE");
    break;
  }
}

int isa_mmu_translate(vaddr_t vaddr, int len, int type, paddr_t *paddr) {  
  //          31:22  21:12     11:0
  // vaddr = {VPN[1],VPN[0],page_offset}
  // Log("vaddr: 0x%x",vaddr);
  bool translate_succ = true;

  word_t VPN_1 = BITS(vaddr, 31, 22);
  word_t VPN_0 = BITS(vaddr, 21, 12);
  word_t page_offset = BITS(vaddr, 11, 0);

  word_t PTE = 0; // PTE value
  

  // first level page table addr
  // the PPN is actually 22 bits, but we only take low 20 bit for now
  word_t flpt = BITS(cpu.satp, 19, 0) << PAGE_SHIFT;

  // use VPN[1] to address in to flpt to get slpt
  PTE = paddr_read(flpt | (VPN_1 << 2), 4);
  // Log("flpt: 0x%x - 0x%x, 0x%x",flpt, flpt | (VPN_1 << 2), PTE);
  // Assert(BIT(flpte, PTE_V), "Invalid First Level PTE for vaddr: 0x%x\n", vaddr);

#define TRANSLATE_EXCEPTION                                                    \
  do {                                                                         \
    set_translate_exception(type, vaddr);                                   \
    translate_succ = false;                                                    \
    goto done;                                                                 \
  } while (0)

  bool superpage = false;
  // check if leaf PTE found
  if (!BIT(PTE, PTE_V)) {
    TRANSLATE_EXCEPTION;
  } else {
    // Log("PTE: 0x%x", PTE);
    bool R = BIT(PTE, PTE_R);
    bool X = BIT(PTE, PTE_X);
    if(R || X) {
      superpage = true;
    }
  }

  if (!superpage) {
    word_t slpt = BITS(PTE, 29, 10) << PAGE_SHIFT;
    // Log("slpt: 0x%x\n", slpt);

    // use VPN[0] to address in to slpt to get PTE
    PTE = paddr_read(slpt | (VPN_0 << 2), 4);
    // printf("NEMU: vaddr: 0x%x pdir: 0x%x flpte_p: 0x%x slpte_p:0x%x slpte: 0x%x\n",
    //        vaddr, flpt, (flpt | (VPN_1 << 2)), (slpt | (VPN_0 << 2)),
    //        PTE);
  }

  // again, only take low 20 bits of PPN
  word_t PPN = BITS(PTE, 29, 10);
  word_t translated_paddr =
      ((superpage)
           ? (BITS(PPN, 19, 10) << 22) | (BITS(vaddr, 21, 12) << PAGE_SHIFT)
           : (PPN << PAGE_SHIFT)) |
      page_offset;

  // check protection bits
  bool V = BIT(PTE, PTE_V);
  bool R = BIT(PTE, PTE_R);
  bool W = BIT(PTE, PTE_W);
  bool X = BIT(PTE, PTE_X);
  // bool U = BIT(PTE, PTE_U);
  // bool G = BIT(PTE, PTE_G);
  bool A = BIT(PTE, PTE_A);
  bool D = BIT(PTE, PTE_D);

  // Assert(V, "vaddr: 0x%x -> paddr: 0x%x is not Valid", vaddr, paddr);
  if(!V) {
    TRANSLATE_EXCEPTION;
  }

  // R W X check
  switch (type) {
  case MEM_TYPE_READ:
    // Assert(R, "vaddr: 0x%x -> paddr: 0x%x is not Readable", vaddr, paddr);
    if (!R) {
      TRANSLATE_EXCEPTION;
    }
    break;
  case MEM_TYPE_WRITE:
    // Assert(W, "vaddr: 0x%x -> paddr: 0x%x is not Writeable", vaddr, paddr);
    if (!W) {
      TRANSLATE_EXCEPTION;
    }
    break;
  case MEM_TYPE_IFETCH:
    // Assert(X, "vaddr: 0x%x -> paddr: 0x%x is not eXecutable", vaddr, paddr);
    if (!X) {
      TRANSLATE_EXCEPTION;
    }
    break;

  default:
    break;
  }

  // // A D check (Svade)
  if (!A || (!D && type == MEM_TYPE_WRITE)) {
    TRANSLATE_EXCEPTION;
  }

  // TODO SUM 

done:
  if (translate_succ) {
    *paddr = translated_paddr;
    // Log("vaddr: %x paddr: %x\n", vaddr, translated_paddr);
    return 0;
  } else {
    // Log("NEMU: vaddr: 0x%x paddr: 0x%x PTE: 0x%x\n", vaddr, translated_paddr, PTE);
    // Log("V: %d, R: %d, W: %d, X: %d, A: %d, D: %d\n", V, R, W, X, A, D);
    // Log("vaddr: %x paddr: ERROR in translation\n", vaddr);
    return -1;
  }
}
