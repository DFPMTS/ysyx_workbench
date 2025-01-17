#include <am.h>
#include <riscv/riscv.h>
#include <klib.h>
#include <klib-macros.h>
#define PGSIZE    4096


static AddrSpace kas = {};
static void* (*pgalloc_usr)(int) = NULL;
static void (*pgfree_usr)(void*) = NULL;
static int vme_enable = 0;

static Area segments[] = {      // Kernel memory mappings
  RANGE(0x80000000, 0x88000000),
  RANGE(0xa0000000, 0xa0000000 + 0x1000)
};
typedef uintptr_t PTE;

#define USER_SPACE RANGE(0x40000000, 0x80000000)

static inline void set_satp(void *pdir) {
  uintptr_t mode = 1ul << (__riscv_xlen - 1);
  asm volatile("csrw satp, %0" : : "r"(mode | ((uintptr_t)pdir >> 12)));
}

static inline uintptr_t get_satp() {
  uintptr_t satp;
  asm volatile("csrr %0, satp" : "=r"(satp));
  return satp << 12;
}

bool vme_init(void* (*pgalloc_f)(int), void (*pgfree_f)(void*)) {
  pgalloc_usr = pgalloc_f;
  pgfree_usr = pgfree_f;

  // note that the pages that pgalloc_f returns have already been clear to 0
  kas.ptr = pgalloc_f(PGSIZE);
  printf("kas.ptr: 0x%x\n", kas.ptr);
  int i;
  for (i = 0; i < LENGTH(segments); i ++) {
    void *va = segments[i].start;
    for (; va < segments[i].end; va += PGSIZE) {
      map(&kas, va, va, 0);
    }
  }

  set_satp(kas.ptr);
  vme_enable = 1;

  return true;
}

void protect(AddrSpace *as) {
  PTE *updir = (PTE*)(pgalloc_usr(PGSIZE));
  as->ptr = updir;
  as->area = USER_SPACE;
  as->pgsize = PGSIZE;
  // map kernel space
  memcpy(updir, kas.ptr, PGSIZE);
}

void unprotect(AddrSpace *as) {
}

void __am_get_cur_as(Context *c) {
  // printf("satp 0x%x saved to context 0x%x\n", get_satp(), c);
  c->pdir = (vme_enable ? (void *)get_satp() : NULL);
}

void __am_switch(Context *c) {
  if (vme_enable && c->pdir != NULL) {
    // printf("satp switch to 0x%x\n",c->pdir);
    set_satp(c->pdir);    
  }
}

#define BITMASK(bits) ((1ul << (bits)) - 1)
#define BITS(x, hi, lo) (((x) >> (lo)) & BITMASK((hi) - (lo) + 1)) // similar to x[hi:lo] in verilog

#define BIT(PTE, N) (((PTE) >> (N)) & 1) // get the bit N of PTE
#define PAGE_SHIFT 12

void map(AddrSpace *as, void *va, void *pa, int prot) {
  // extract VPN[1], VPN[0]
  uintptr_t vaddr = (uintptr_t) va;
  uintptr_t VPN_1 = BITS(vaddr, 31, 22);
  uintptr_t VPN_0 = BITS(vaddr, 21, 12);
  // extract PPN
  uintptr_t paddr = (uintptr_t) pa;
  uintptr_t PPN = BITS(paddr, 31, 12);

  // addr of first level PTE
  uintptr_t *flpte_p = (uintptr_t *)((uintptr_t)as->ptr | (VPN_1 << 2));

  if (!(*flpte_p & PTE_V)) {
    // allocate a page for second level PTE
    uintptr_t slpt_addr = (uintptr_t)pgalloc_usr(PGSIZE);
    uintptr_t slpt_PPN = BITS(slpt_addr, 31, 12);
    // set Valid bit for this PTE
    *flpte_p = (slpt_PPN << 10) | PTE_V;
  }
  uintptr_t flpte = *flpte_p;
  uintptr_t *slpte_p = (uintptr_t *)((BITS(flpte, 29, 10) << PAGE_SHIFT) | (VPN_0 << 2));
  if((*slpte_p & PTE_V)){
    printf("vaddr: 0x%x pdir: 0x%x flpte_p: %p flpte: 0x%x slpte_p: %p slpte: 0x%x\n",vaddr,as->ptr,flpte_p,flpte,slpte_p,*slpte_p);
  }
  assert(!(*slpte_p & PTE_V));

  // set PPN & Valid bit & RWX
  *slpte_p = (PPN << 10) | PTE_R | PTE_W | PTE_X | PTE_V;

  if (vaddr == 0x40332000) {
    // printf("map vaddr: 0x%x pdir: 0x%x flpte_p: %p flpte: 0x%x slpte_p: %p slpte: 0x%x\n",vaddr,as->ptr,flpte_p,flpte,slpte_p,*slpte_p);
  }
}

Context *ucontext(AddrSpace *as, Area kstack, void *entry) {
  Context *c = kcontext(kstack, entry, NULL);
  c->pdir = as->ptr;
  return c;
}
