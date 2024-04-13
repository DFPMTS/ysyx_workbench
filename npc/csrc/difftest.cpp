#include "difftest.hpp"
#include "cpu.hpp"
#include "mem.hpp"
#include <dlfcn.h>

ref_difftest_init_t ref_difftest_init;
ref_difftest_memcpy_t ref_difftest_memcpy;
ref_difftest_regcpy_t ref_difftest_regcpy;
ref_difftest_exec_t ref_difftest_exec;
ref_difftest_raise_intr_t ref_difftest_raise_intr;

void init_difftest() {
  const char *nemu_so = "/home/dfpmts/Documents/ysyx-workbench/nemu/build/"
                        "riscv32-nemu-interpreter-so";
  auto ref_handle = dlopen(nemu_so, RTLD_LAZY);
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
  ref_difftest_memcpy(RESET_VECTOR, mem, 0x08000000, DIFFTEST_TO_REF);
  difftest_context_t dut;
  get_context(&dut);
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
  for (int i = 0; i < 16; ++i) {
    succ &= difftest_check_reg(reg_name(i), dut->pc, ref->gpr[i], dut->gpr[i]);
  }
  succ &= difftest_check_reg("pc", dut->pc, ref->pc, dut->pc);
  return succ;
}

void trace_and_difftest() {
  difftest_context_t dut;
  difftest_context_t ref;
  get_context(&dut);
  ref_difftest_regcpy(&ref, DIFFTEST_TO_DUT);
  // isa_reg_display(&dut);
  if (!check_context(&ref, &dut)) {
    assert(0);
  }
}