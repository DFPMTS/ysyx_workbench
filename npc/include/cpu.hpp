#ifndef CPU_HPP
#define CPU_HPP

#include "Vtop.h"
#include "config.hpp"
#include <Vtop___024root.h>
#include <cstdint>
#include <functional>
#include <iostream>
#include <verilated.h>
#include <verilated_fst_c.h>

extern Vtop *top;
extern VerilatedFstC *fst;

#define SIM_T 10
extern uint64_t eval_time;
extern bool begin_wave;
extern bool begin_log;

#define CONCAT(x, y) concat_temp(x, y)
#define concat_temp(x, y) x##y
static uint32_t dummy = 0;

using rtlreg_t = word_t;
using vaddr_t = word_t;

struct difftest_context_t {
  struct {
    rtlreg_t _32;
  } gpr[32];

  rtlreg_t crmd, prmd, euen, ecfg;
  rtlreg_t era, badv, eentry;
  rtlreg_t tlbidx, tlbehi, tlbelo0, tlbelo1;
  rtlreg_t asid, pgdl, pgdh;
  rtlreg_t save0, save1, save2, save3;
  rtlreg_t tid, tcfg, tval; //, ticlr;
  rtlreg_t llbctl, tlbrentry, dmw0, dmw1;
  rtlreg_t estat;
  vaddr_t idle_pc;

  rtlreg_t stable_counter_id;
  rtlreg_t stable_counter_lo;
  rtlreg_t stable_counter_hi;

  rtlreg_t ll_bit;
  bool inst_idle;

  vaddr_t pc;
  bool INTR;
};

extern std::function<uint32_t(int)> gpr;
extern std::function<uint32_t(void)> PC;

const char *reg_name(int id);
void get_context(difftest_context_t *dut);

void isa_reg_display(difftest_context_t *ctx);
void init_cpu();
void cpu_step();

#endif