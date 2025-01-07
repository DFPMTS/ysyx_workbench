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

#define CONCAT(x, y) concat_temp(x, y)
#define concat_temp(x, y) x##y
static uint32_t dummy = 0;

struct difftest_context_t {
  word_t gpr[32];
  word_t pc;
  word_t priv;
  word_t stvec;
  word_t sscratch;
  word_t sepc;
  word_t scause;
  word_t stval;
  word_t satp;
  word_t mstatus;
  word_t medeleg;
  word_t mideleg;
  word_t mie;
  word_t mtvec;
  word_t menvcfg;
  word_t mscratch;
  word_t mepc;
  word_t mcause;
  word_t mtval;
  word_t mip;
};

extern std::function<uint32_t(int)> gpr;
extern std::function<uint32_t(void)> PC;

const char *reg_name(int id);
void get_context(difftest_context_t *dut);

void isa_reg_display(difftest_context_t *ctx);
void init_cpu();
void cpu_step();

#define UART_BASE 0x10000000
#define UART_SIZE 9

#endif