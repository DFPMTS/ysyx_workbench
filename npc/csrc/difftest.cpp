#include "difftest.hpp"
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

difftest_context_t ref;
difftest_context_t dut;

void init_difftest(const char *diff_so_file) {
  assert(diff_so_file != NULL);
  auto ref_handle = dlopen(diff_so_file, RTLD_LAZY);
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
  ref_difftest_memcpy(RESET_VECTOR, mem, 0x08000000, DIFFTEST_TO_REF);
#else
  ref_difftest_memcpy(FLASH_BASE, flash, FLASH_SIZE, DIFFTEST_TO_REF);
#endif
  difftest_context_t dut;
  get_context(&dut);
  dut.pc = RESET_VECTOR;
  ref_difftest_regcpy(&dut, DIFFTEST_TO_REF);
}

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

bool check_context(difftest_context_t *ref, difftest_context_t *dut) {
  bool succ = true;
  for (int i = 0; i < 32; ++i) {
    succ &= difftest_check_reg(reg_name(i), dut->pc, ref->gpr[i], dut->gpr[i]);
  }
  // succ &= difftest_check_reg("pc", dut->pc, ref->pc, dut->pc);
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
  difftest_step();
  get_context(&dut);
  ref_difftest_regcpy(&ref, DIFFTEST_TO_DUT);
  if (!check_context(&ref, &dut)) {
    isa_reg_display(&dut);
    Log("Difftest failed\n");
    running.store(false);
  }
  // isa_reg_display(&ref);
}

void difftest_step() {
  if (!access_device) {
    ref_difftest_exec(1);
  } else {
    get_context(&dut);
    ref_difftest_regcpy(&dut, DIFFTEST_TO_REF);
    access_device = false;
  }
}

void trace_pc() {
  ref_difftest_regcpy(&ref, DIFFTEST_TO_DUT);
  trace_write("%08x\n", ref.pc);
  difftest_step();
}