#include "Multiplier_tb.h"

#include "verilated.h"
#include "verilated_fst_c.h"
#include <cmath>
#include <cstdint>
#include <cstdlib>
// object MULOp extends HasDecodeConfig {
//   def MUL    = 0.U(OpcodeWidth.W)
//   def MULH   = 1.U(OpcodeWidth.W)
//   def MULHSU = 2.U(OpcodeWidth.W)
//   def MULHU  = 3.U(OpcodeWidth.W)
// }

uint32_t ref(uint8_t opcode, uint32_t src1, uint32_t src2, uint32_t out) {
  switch (opcode) {
  case 0:
    return (uint32_t)((int32_t)src1 * (int32_t)src2);
  case 1:
    return (uint32_t)(((int64_t)(int32_t)src1 * (int64_t)(int32_t)src2) >> 32);
  case 2:
    return (uint32_t)(((int64_t)(int32_t)src1 * (int64_t)src2) >> 32);
  case 3:
    return (uint32_t)(((uint64_t)src1 * (uint64_t)src2) >> 32);
  default:
    printf("Unknown opcode\n");
    exit(-1);
  }
}

int main() {
  Verilated::traceEverOn(true);

  VerilatedFstC *fst = new VerilatedFstC;
  Multiplier_tb *top = new Multiplier_tb;

  top->trace(fst, 99);
  fst->open("Multiplier_tb.fst");

  top->reset = 1;
  top->eval();
  top->reset = 0;

  int T = 10000000;
  int opcode = 0;
  uint32_t src1 = 0;
  int src1Stride = 1;
  uint32_t src2 = 0;
  int src2Stride = 3;
  int cycles = 0;
  while (T--) {
    ++cycles;
    top->io_src1 = src1;
    top->io_src2 = src2;
    top->io_opcode = opcode;
    top->clock = 0;
    top->eval();
    // fst->dump(10 * cycles - 2);

    top->clock = 1;
    top->eval();
    // fst->dump(10 * cycles);

    top->clock = 0;
    top->eval();
    // fst->dump(10 * cycles + 2);
    auto refValue = ref(opcode, src1, src2, top->io_out);
    // * Green if pass, RED if fail
    if (refValue == top->io_out) {
      //   printf("\033[0;32m");
      //   printf("PASS\n");
      //   printf("\033[0;0m");
    } else {
      printf("opcode: %u src1: %d/%u, src2: %d/%u, result: %d/%u\n", opcode,
             (int32_t)src1, src1, (int32_t)src2, src2, (int32_t)top->io_out,
             top->io_out);
      // * print the binary version of the xor diff
      printf("expected: %d/%u\n", (int16_t)refValue, refValue);
      auto diff = refValue ^ top->io_out;
      printf("Difference (binary): ");
      for (int i = 31; i >= 0; --i) {
        printf("%c", (diff & (1 << i)) ? '1' : '0');
        if (i % 4 == 0)
          printf(" ");
      }
      printf("\n");
      printf("\033[0;31m");
      printf("FAIL\n");
      printf("\033[0;0m");
      fst->close();
      exit(-1);
    }
    src1 += rand();
    src2 += rand();
    // src1++;
    // if (src1 == 0) {
    //   src2++;
    // }
    opcode++;
    if (opcode == 4)
      opcode = 0;
  }
  printf("\033[0;32m");
  printf("PASS\n");
  printf("\033[0;0m");
  fst->close();
  delete top;
  delete fst;
  return 0;
}