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
  uint32_t gpr[32];
  uint32_t pc;
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