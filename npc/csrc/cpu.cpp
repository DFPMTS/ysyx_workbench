#include "cpu.hpp"
#include "SimState.hpp"
#include "difftest.hpp"
#include "disasm.hpp"
#include <cstdint>
#include <cstdio>
#include <functional>

Vtop *top;
VerilatedFstC *fst;
uint64_t eval_time;

static const char *regs[] = {"r0", "ra", "tp", "sp", "a0", "a1", "a2", "a3",
                             "a4", "a5", "a6", "a7", "t0", "t1", "t2", "t3",
                             "t4", "t5", "t6", "t7", "t8", " x", "fp", "s0",
                             "s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8"};

static uint32_t *gprs[32];
bool begin_wave = false;
bool begin_log = false;

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
  printf("------------------------\n");
  printf("crmd: %08X\n", ctx->crmd);
  printf("prmd: %08X\n", ctx->prmd);
  printf("euen: %08X\n", ctx->euen);
  printf("ecfg: %08X\n", ctx->ecfg);
  printf("era:  %08X\n", ctx->era);
  printf("badv: %08X\n", ctx->badv);
  printf("eentry: %08X\n", ctx->eentry);
  printf("tlbidx: %08X\n", ctx->tlbidx);
  printf("tlbehi: %08X\n", ctx->tlbehi);
  printf("tlbelo0: %08X\n", ctx->tlbelo0);
  printf("tlbelo1: %08X\n", ctx->tlbelo1);
  printf("asid: %08X\n", ctx->asid);
  printf("pgdl: %08X\n", ctx->pgdl);
  printf("pgdh: %08X\n", ctx->pgdh);
  printf("save0: %08X\n", ctx->save0);
  printf("save1: %08X\n", ctx->save1);
  printf("save2: %08X\n", ctx->save2);
  printf("save3: %08X\n", ctx->save3);
  printf("tid: %08X\n", ctx->tid);
  printf("tcfg: %08X\n", ctx->tcfg);
  printf("tval: %08X\n", ctx->tval);
  printf("llbctl: %08X\n", ctx->llbctl);
  printf("tlbrentry: %08X\n", ctx->tlbrentry);
  printf("dmw0: %08X\n", ctx->dmw0);
  printf("dmw1: %08X\n", ctx->dmw1);
  printf("estat: %08X\n", ctx->estat);
  printf("tid: %08X\n", ctx->tid);
  printf("tval: %08X\n", ctx->tval);
  printf("========================\n");
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
    dut->gpr[i]._32 = gpr(i);
  }
  dut->pc = PC();
  dut->crmd = *state.csr.crmd;
  dut->prmd = *state.csr.prmd;
  dut->euen = *state.csr.euen;
  dut->ecfg = *state.csr.ecfg;

  dut->era = *state.csr.era;
  dut->badv = *state.csr.badv;
  dut->eentry = *state.csr.eentry;

  dut->tlbidx = *state.csr.tlbidx;
  dut->tlbehi = *state.csr.tlbehi;
  dut->tlbelo0 = *state.csr.tlbelo0;
  dut->tlbelo1 = *state.csr.tlbelo1;

  dut->asid = *state.csr.asid;
  dut->pgdl = *state.csr.pgdl;
  dut->pgdh = *state.csr.pgdh;

  dut->save0 = *state.csr.save0;
  dut->save1 = *state.csr.save1;
  dut->save2 = *state.csr.save2;
  dut->save3 = *state.csr.save3;

  dut->tid = *state.csr.tid;
  dut->tcfg = *state.csr.tcfg;
  dut->tval = *state.csr.tval;

  dut->llbctl = *state.csr.llbctl;
  dut->tlbrentry = *state.csr.tlbrentry;
  dut->dmw0 = *state.csr.dmw0;
  dut->dmw1 = *state.csr.dmw1;

  dut->estat = *state.csr.estat;
}

void cpu_step() {
  static uint64_t sim_time = 1;
  top->clock = 0;
  cpu_update_hw_intr();
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
  state.bindUops();
#ifdef NVBOARD
  nvboard_bind_all_pins(top);
#endif
  init_regs();

#ifdef WAVE
  fst = new VerilatedFstC;
  Verilated::traceEverOn(true);
  top->trace(fst, 99);
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

uint8_t calculate_isr();
void serial_rx_collect();

void cpu_update_hw_intr() {
  static int uart_counter = 0;
  static uint8_t intr = 0;
  ++uart_counter;
  if (uart_counter > 10000) {
    serial_rx_collect();
    uart_counter = 0;
  }
  if ((calculate_isr() & 0x1) == 0) {
    // printf("UART INT\n");
    // fflush(stdout);
    intr |= 0x2;
  } else {
    intr &= ~0x2;
  }
  top->io_hwIntr = intr;
}