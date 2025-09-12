#include "FreeList_tb.h"
#include "verilated.h"
#include "verilated_fst_c.h"
#include <cstdint>
#include <iostream>
#include <queue>
#include <vector>

#define WAVE
#define SIM_T 10
bool begin_wave = true;
VerilatedFstC *fst = nullptr;
uint64_t eval_time;

// * testing the FreeList module
FreeList_tb *top = nullptr;

void step() {
  static uint64_t sim_time = 1;
  top->clock = 0;

  top->eval();
#ifdef WAVE
  if (begin_wave) {
    eval_time = sim_time * SIM_T - 2;
    fst->dump(eval_time);
  }
#endif

  top->clock = 1;
  top->eval();
#ifdef WAVE
  if (begin_wave) {
    eval_time = sim_time * SIM_T;
    fst->dump(eval_time);
  }
#endif

  top->clock = 0;
  top->eval();
#ifdef WAVE
  if (begin_wave) {
    eval_time = sim_time * SIM_T + 2;
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

void reset() {
  top->reset = 1;
  step();
  top->reset = 0;
  step();
}

int main() {
  top = new FreeList_tb;
  fst = new VerilatedFstC;
  Verilated::traceEverOn(true);
  top->trace(fst, 5);
  fst->open("FreeList.fst");

  int T = 0;
  reset();
  std::queue<uint32_t> pregs;
  uint32_t x10_map = 0;
  while (T++ < 300) {
    top->io_IN_renameReqValid_0 = 1;
    top->io_IN_renameReqValid_1 = 1;
    if (pregs.size() >= 2) {
      top->io_IN_commitValid_0 = 1;
      top->io_IN_commitPReg_0 = pregs.front();
      top->io_IN_commitPrevPReg_0 = x10_map;
      top->io_IN_commitRd_0 = 10;
      pregs.pop();

      top->io_IN_commitValid_1 = 1;
      top->io_IN_commitPReg_1 = pregs.front();
      top->io_IN_commitPrevPReg_1 = x10_map;
      top->io_IN_commitRd_1 = 10;
      pregs.pop();

      x10_map = top->io_IN_commitPReg_1;
    } else {
      top->io_IN_commitValid_0 = 0;
      top->io_IN_commitValid_1 = 0;
    }

    top->eval();
    if (!top->io_OUT_renameStall) {
      pregs.push(top->io_OUT_renamePReg_0);
      pregs.push(top->io_OUT_renamePReg_1);
      std::cout << "[0]: " << (uint32_t)top->io_OUT_renamePReg_0 << " -- ";
      std::cout << "[1]: " << (uint32_t)top->io_OUT_renamePReg_1 << std::endl;
    }
    step();
  }
  fst->close();
  delete top;
  return 0;
}