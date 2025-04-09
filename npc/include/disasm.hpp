#ifndef DISASM_HPP
#define DISASM_HPP

#include <stdint.h>

void init_disasm();
void disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte);

#endif