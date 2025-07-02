#include "difftest.hpp"
#include "SimState.hpp"
#include "config.hpp"
#include "cpu.hpp"
#include "debug.hpp"
#include "ftrace.hpp"
#include "itrace.hpp"
#include "mem.hpp"
#include "status.hpp"
#include <dlfcn.h>

ref_difftest_init_t ref_difftest_init;
ref_difftest_memcpy_t ref_difftest_memcpy;
ref_difftest_regcpy_t ref_difftest_regcpy;
ref_difftest_exec_t ref_difftest_exec;
ref_difftest_raise_intr_t ref_difftest_raise_intr;

LA32R_CPU_State ref;
difftest_context_t dut;

void transform_dut(difftest_context_t *dut, LA32R_CPU_State *dut_la) {
  // Transform the dut context to match the ref context
  for (int i = 0; i < 32; ++i) {
    dut_la->gpr[i]._32 = dut->gpr[i];
  }
  dut_la->idle_pc = state.lastPC;
  dut_la->pc = PC();
  // printf("DUT_LA: PC = %08X\n", dut_la->idle_pc);
}

void *ref_handle = NULL;

void init_difftest(const char *diff_so_file) {
  assert(diff_so_file != NULL);
  ref_handle = dlopen(diff_so_file, RTLD_LAZY);
  assert(ref_handle);

  ref_difftest_init = (ref_difftest_init_t)dlsym(ref_handle, "difftest_init");
  assert(ref_difftest_init);

  ref_difftest_memcpy =
      (ref_difftest_memcpy_t)dlsym(ref_handle, "difftest_memcpy");
  assert(ref_difftest_memcpy);

  ref_difftest_regcpy =
      (ref_difftest_regcpy_t)dlsym(ref_handle, "difftest_regcpy");
  assert(ref_difftest_regcpy);

  ref_difftest_exec = (ref_difftest_exec_t)dlsym(ref_handle, "difftest_exec");
  assert(ref_difftest_exec);

  ref_difftest_raise_intr =
      (ref_difftest_raise_intr_t)dlsym(ref_handle, "difftest_raise_intr");
  assert(ref_difftest_raise_intr);

  ref_difftest_init(0);
#ifdef NPC
  ref_difftest_memcpy(MEM_BASE, mem, MEM_SIZE, DIFFTEST_TO_REF);
#else
  ref_difftest_memcpy(FLASH_BASE, flash, FLASH_SIZE, DIFFTEST_TO_REF);
#endif
  difftest_context_t dut;
  get_context(&dut);
  dut.pc = RESET_VECTOR;
  LA32R_CPU_State dut_la;
  transform_dut(&dut, &dut_la);

  ref_difftest_regcpy(&dut_la, DIFFTEST_TO_REF, false);
}

void close_difftest() { dlclose(ref_handle); }

static inline bool difftest_check_reg(const char *name, vaddr_t pc, word_t ref,
                                      word_t dut) {
  if (ref != dut) {
    printf("%s is different after executing instruction at pc = 0x%08X, right "
           "= 0x%08X, wrong = 0x%08X, diff = 0x%08X\n",
           name, pc, ref, dut, ref ^ dut);
    return false;
  }
  return true;
}

bool check_context(LA32R_CPU_State *ref, difftest_context_t *dut) {
  bool succ = true;
  for (int i = 0; i < 32; ++i) {
    succ &=
        difftest_check_reg(reg_name(i), dut->pc, ref->gpr[i]._32, dut->gpr[i]);
  }
  succ &= difftest_check_reg("pc", dut->pc, ref->pc, dut->pc);
  // succ &= difftest_check_reg("priv", dut->pc, ref->priv, dut->priv);
  // succ &= difftest_check_reg("stvec", dut->pc, ref->stvec, dut->stvec);
  // succ &= difftest_check_reg("sscratch", dut->pc, ref->sscratch,
  // dut->sscratch); succ &= difftest_check_reg("sepc", dut->pc, ref->sepc,
  // dut->sepc); succ &= difftest_check_reg("scause", dut->pc, ref->scause,
  // dut->scause); succ &= difftest_check_reg("stval", dut->pc, ref->stval,
  // dut->stval); succ &= difftest_check_reg("satp", dut->pc, ref->satp,
  // dut->satp); succ &= difftest_check_reg("mstatus", dut->pc, ref->mstatus,
  // dut->mstatus); succ &= difftest_check_reg("medeleg", dut->pc, ref->medeleg,
  // dut->medeleg); succ &= difftest_check_reg("mideleg", dut->pc, ref->mideleg,
  // dut->mideleg); succ &= difftest_check_reg("mie", dut->pc, ref->mie,
  // dut->mie); succ &= difftest_check_reg("mtvec", dut->pc, ref->mtvec,
  // dut->mtvec); succ &= difftest_check_reg("menvcfg", dut->pc, ref->menvcfg,
  // dut->menvcfg); succ &= difftest_check_reg("mscratch", dut->pc,
  // ref->mscratch, dut->mscratch); succ &= difftest_check_reg("mepc", dut->pc,
  // ref->mepc, dut->mepc); succ &= difftest_check_reg("mcause", dut->pc,
  // ref->mcause, dut->mcause); succ &= difftest_check_reg("mtval", dut->pc,
  // ref->mtval, dut->mtval); succ &= difftest_check_reg("mip", dut->pc,
  // ref->mip, dut->mip);

  return succ;
}

void trace(uint32_t pc, uint32_t inst) {
#ifdef ITRACE
  static char buf[128];
  itrace_generate(buf, pc, inst);
  log_write("%s\n", buf);
#endif
#ifdef FTRACE
  if (JUMP) {
    ftrace_log(PC, DNPC, INST, RD, RS1, IMM);
  }
#endif
}

void difftest() {
  // * access_device: override the ref
  if (access_device) {
    // printf("Accessing device, override the ref\n");
    get_context(&dut);
    LA32R_CPU_State dut_la;
    transform_dut(&dut, &dut_la);
    ref_difftest_regcpy(&dut_la, DIFFTEST_TO_REF, false);
    access_device = false;
    return;
  }

  ref_difftest_exec(1);

  get_context(&dut);
  ref_difftest_regcpy(&ref, DIFFTEST_TO_DUT, false);
  // isa_reg_display(&ref);
  if (!check_context(&ref, &dut)) {
    isa_reg_display(&dut);
    Log("Difftest failed\n");
    stop = Stop::DIFFTEST_FAILED;
    running.store(false);
  }
}

void difftest_step() {
  if (!access_device) {
    ref_difftest_exec(1);
  } else {
    get_context(&dut);
    ref_difftest_regcpy(&dut, DIFFTEST_TO_REF, false);
    access_device = false;
  }
}

void trace_pc() {
  ref_difftest_regcpy(&ref, DIFFTEST_TO_DUT, false);
  trace_write("%08x\n", ref.pc);
  difftest_step();
}