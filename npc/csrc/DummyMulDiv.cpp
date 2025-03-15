#include "debug.hpp"
#include <stdint.h>

#define SEXT(x, len)                                                           \
  ({                                                                           \
    struct {                                                                   \
      int64_t n : len;                                                         \
    } __x = {.n = x};                                                          \
    (uint64_t)__x.n;                                                           \
  })

extern "C" {
uint32_t dummyMul(uint8_t opcode, uint32_t src1, uint32_t src2) {
  uint32_t out = 0;
  switch (opcode) {
  case 0: // mul
    out = src1 * src2;
    break;

  case 1: // mulh
    out = (SEXT(src1, 32) * SEXT(src2, 32)) >> 32;
    break;

  case 2: // mulhsu
    out = (SEXT(src1, 32) * (uint64_t)src2) >> 32;
    break;

  case 3: // mulhu
    out = ((uint64_t)src1 * (uint64_t)src2) >> 32;
    break;

  default:
    Assert(0);
    break;
  }
  return out;
}
int dummyDiv(uint8_t opcode, uint32_t src1, uint32_t src2) {
  uint32_t out = 0;
  switch (opcode) {
  case 0: // div
    if (src2 == 0) {
      out = -1;
    } else if (src1 == INT32_MIN && src2 == -1) {
      out = INT32_MIN;
    } else {
      out = (int32_t)src1 / (int32_t)src2;
    }
    break;

  case 1: // divu
    out = (src2 == 0) ? -1 : src1 / src2;
    break;

  case 2: // rem
    if (src2 == 0) {
      out = src1;
    } else if (src1 == INT32_MIN && src2 == -1) {
      out = 0;
    } else {
      out = (int32_t)src1 % (int32_t)src2;
    }
    break;

  case 3: // remu
    out = (src2 == 0) ? src1 : src1 % src2;
    break;

  default:
    break;
  }
  return out;
}
}