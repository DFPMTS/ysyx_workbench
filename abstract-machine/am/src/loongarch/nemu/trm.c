#include <am.h>
#include <klib-macros.h>
#include "nemu.h"

extern char _heap_start;
int main(const char *args);

extern char _pmem_start;

Area heap = RANGE(&_heap_start, PMEM_END);
#ifndef MAINARGS
#define MAINARGS ""
#endif
static const char mainargs[] = MAINARGS;

void putch(char ch) { 
  while(true) {
    uint8_t status = inb(SERIAL_PORT + 5);
    if((status & 0x40)) {
      break;
    }
  }
  outb(SERIAL_PORT, ch); 
}

void halt(int code) {
  asm volatile("add.w $r10, %0, $r0" : :"r"(code));
  asm volatile("syscall 0x11");
  while (1);
}

void _trm_init() {
  // putstr("_trm_init\n");
  int ret = main(mainargs);
  halt(ret);
}
