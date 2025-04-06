#ifndef DIFFTEST_HPP
#define DIFFTEST_HPP

#include "Vtop.h"
#include "cpu.hpp"
#include "mem.hpp"

enum { DIFFTEST_TO_DUT, DIFFTEST_TO_REF };

typedef void (*ref_difftest_memcpy_t)(paddr_t addr, void *buf, size_t n,
                                      bool direction);
typedef void (*ref_difftest_regcpy_t)(void *dut, bool direction);
typedef void (*ref_difftest_exec_t)(uint64_t n);
typedef void (*ref_difftest_raise_intr_t)(word_t NO);
typedef void (*ref_difftest_init_t)(int port);

extern ref_difftest_init_t ref_difftest_init;
extern ref_difftest_memcpy_t ref_difftest_memcpy;
extern ref_difftest_regcpy_t ref_difftest_regcpy;
extern ref_difftest_exec_t ref_difftest_exec;
extern ref_difftest_raise_intr_t ref_difftest_raise_intr;

extern difftest_context_t ref;
extern difftest_context_t dut;

void init_difftest(const char *diff_so_file);
void close_difftest();
void trace(uint32_t pc, uint32_t inst);
void difftest();
void difftest_step();
void trace_pc();

#endif