#include "Vtop.h"
#include "status.hpp"
#include "verilated_vcd_c.h"
#include <atomic>
#include <cassert>
#define WAVE
#define SIM_T 10
uint8_t mem[1 << 20];
extern "C" void flash_read(int32_t addr, int32_t *data) { assert(0); }
extern "C" void mrom_read(int32_t addr, int32_t *data) {
  *data = *(uint32_t *)(mem + addr - 0x20000000);
}

static uint32_t image[128] = {
    0x00000297, // auipc t0,0
    // 0x00028823, // sb  zero,16(t0)
    0x0102c503, // lbu a0,16(t0)
    0x00100073, // ebreak (used as nemu_trap)
    0xdeadbeef, // some data
};

Vtop *top;
VerilatedVcdC *vcd;

void cpu_step() {
  static uint64_t sim_time = 1;
  uint64_t eval_time;
  top->clock = 0;

  top->eval();
#ifdef WAVE
  eval_time = sim_time * SIM_T - 2;
  vcd->dump(eval_time);
#endif

  top->clock = 1;
  top->eval();
#ifdef WAVE
  eval_time = sim_time * SIM_T;
  vcd->dump(eval_time);
#endif

  top->clock = 0;
  top->eval();
#ifdef WAVE
  eval_time = sim_time * SIM_T + 2;
  vcd->dump(eval_time);
#endif

#ifdef WAVE
  vcd->flush();
#endif
  ++sim_time;
}

void cpu_reset() {

  int T = 3;
  top->reset = 1;
  while (T--) {
    cpu_step();
  }
  top->reset = 0;
  running = true;
  top->eval();
}

extern "C" {
void nemu_break() { running = false; }
}

void load_img(const char *img) {
  FILE *fd = NULL;
  if (img) {
    fd = fopen(img, "rb");
  }
  bool succ = true;
  if (!img) {
    printf("No img provided, using built-in one.\n");
    succ = false;
  } else if (!fd) {
    printf("Unable to open img: %s\n", img);
    succ = false;
  }

  if (succ) {
    fseek(fd, 0, SEEK_END);
    auto size = ftell(fd);
    fseek(fd, 0, SEEK_SET);
    assert(fread(mem, 1, size, fd) == size);
  } else {
    memcpy(mem, image, sizeof(image));
  }
}

int main(int argc, char *argv[]) {
  if (argc == 1) {
    load_img(NULL);
  } else {
    load_img(argv[1]);
  }

  Verilated::commandArgs(argc, argv);
  Verilated::traceEverOn(true);
  top = new Vtop;
  vcd = new VerilatedVcdC;
  top->trace(vcd, 5);
  vcd->open("soc_wave.vcd");

  cpu_reset();
  while (running) {
    cpu_step();
  }
  fprintf(stderr, "Hit trap\n");
  vcd->close();
}