#include <am.h>
#include <riscv/riscv.h>
#include <klib.h>

static Context* (*user_handler)(Event, Context*) = NULL;
void __am_get_cur_as(Context *c);
void __am_switch(Context *c);

__attribute_maybe_unused__ static void print_context(Context *c)
{
  printf("am_irq Context:\n");
  printf("sp:       0x%x\n",c->gpr[2]);
  printf("sscratch: 0x%x\n",c->sscratch);
  printf("\n");
}

Context* __am_irq_handle(Context *c) {
  if (user_handler) {
    Event ev = {0};
    ev.event = EVENT_ERROR;
    switch (c->mcause) {
    case 11: // ecall from m
      if (c->GPR1 == -1) {
        // yield
        ev.event = EVENT_YIELD;
        c->mepc += 4;
        break;
      } else if (c->GPR1 >= 0 && c->GPR1 <= 19){
        ev.event = EVENT_SYSCALL;
        c->mepc += 4;        
      }      
    }

    c = user_handler(ev, c);
    assert(c != NULL);
  }
  return c;
}

extern void __am_asm_trap(void);

bool cte_init(Context*(*handler)(Event, Context*)) {
  // initialize exception entry
  asm volatile("csrw mtvec, %0" : : "r"(__am_asm_trap));

  // register event handler
  user_handler = handler;

  return true;
}

#if __riscv_xlen == 32
#define XLEN  4
#else
#define XLEN  8
#endif 

#ifdef __riscv_e
#define NR_REGS 16
#else
#define NR_REGS 32
#endif

Context *kcontext(Area kstack, void (*entry)(void *), void *arg) {  
  Context **cp = kstack.start;
  Context *c = kstack.end - sizeof(Context);

  c->mepc = (uintptr_t)entry;
  c->mstatus = 0x1800;
  c->gpr[2] = (uintptr_t)kstack.end;
  c->gpr[10] = (uintptr_t)arg;
  c->sscratch = 0;

  // kernel space
  c->pdir = NULL;

  *cp = c;
  return c;
}

void yield() {
#ifdef __riscv_e
  asm volatile("li a5, -1; ecall");
#else
  asm volatile("li a7, -1; ecall");
#endif
}

bool ienabled() {
  return false;
}

void iset(bool enable) {
}
