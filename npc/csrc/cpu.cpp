#include "cpu.hpp"
#include "difftest.hpp"
#include "disasm.hpp"
#include <cstdint>
#include <functional>

Vtop *top;
VerilatedFstC *fst;
uint64_t eval_time;

static const char *regs[] = {"$0", "ra", "sp",  "gp",  "tp", "t0", "t1", "t2",
                             "s0", "s1", "a0",  "a1",  "a2", "a3", "a4", "a5",
                             "a6", "a7", "s2",  "s3",  "s4", "s5", "s6", "s7",
                             "s8", "s9", "s10", "s11", "t3", "t4", "t5", "t6"};

static uint32_t *gprs[32];
bool begin_wave = false;

void check_gpr_bound(int id) { assert(id >= 0 && id < 32); }

void isa_reg_display(difftest_context_t *ctx) {

  static char buf[128];
  // disassemble(buf, 128, PC, (uint8_t *)&INST, 4);

  printf("------------------------\n");
  printf("|PC:    %08X       |\n", ctx->pc);
  printf("------------------------\n");
  printf("|       %s\n", buf);
  printf("------------------------\n");
  for (int i = 0; i < 32; ++i) {
    printf("%s\t%08X\t%d\n", reg_name(i), ctx->gpr[i], ctx->gpr[i]);
  }
  printf("============================================\n");
}
const char *reg_name(int id) {
  check_gpr_bound(id);
  return regs[id];
}

void init_regs() {
#define MAP_REG(i) gprs[i] = &REG(i);
  // MAP_REG(0);
  // MAP_REG(1);
  // MAP_REG(2);
  // MAP_REG(3);

  // MAP_REG(4);
  // MAP_REG(5);
  // MAP_REG(6);
  // MAP_REG(7);

  // MAP_REG(8);
  // MAP_REG(9);
  // MAP_REG(10);
  // MAP_REG(11);

  // MAP_REG(12);
  // MAP_REG(13);
  // MAP_REG(14);
  // MAP_REG(15);
}

std::function<uint32_t(int)> gpr;
std::function<uint32_t(void)> PC;

void get_context(difftest_context_t *dut) {
  for (int i = 0; i < 32; ++i) {
    dut->gpr[i] = gpr(i);
  }
  dut->pc = PC();
}

void cpu_step() {
  static uint64_t sim_time = 1;
  top->clock = 0;

  top->eval();
  eval_time = sim_time * SIM_T - 2;
#ifdef WAVE
  if (begin_wave) {
    fst->dump(eval_time);
  }
#endif

  top->clock = 1;
  top->eval();
  eval_time = sim_time * SIM_T;
#ifdef WAVE
  if (begin_wave) {
    fst->dump(eval_time);
  }
#endif

  top->clock = 0;
  top->eval();
  eval_time = sim_time * SIM_T + 2;
#ifdef WAVE
  if (begin_wave) {
    fst->dump(eval_time);
  }
#endif

#ifdef WAVE
  if (begin_wave) {
    fst->flush();
  }
#endif
  ++sim_time;
}

void nvboard_bind_all_pins(Vtop *top);

void init_cpu() {
  top = new Vtop;
#ifdef NVBOARD
  nvboard_bind_all_pins(top);
#endif
  init_regs();

#ifdef WAVE
  fst = new VerilatedFstC;
  Verilated::traceEverOn(true);
  top->trace(fst, 5);
  fst->open("wave.fst");
#endif

  int T = 10;
  top->reset = 1;
  while (T--) {
    cpu_step();
  }
  running.store(true);
  top->reset = 0;
  top->eval();
}