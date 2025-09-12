#include "mem.hpp"
#include "cpu.hpp"
#include "debug.hpp"
#include "status.hpp"
#include <cassert>
#include <cstdint>
#include <cstring>
#include <ctime>
#include <iostream>

#define OFFSET 0x80000000
#define SIZE 0x08000000

#define DEVICE_BASE 0xa0000000

#define RTC_ADDR (DEVICE_BASE + 0x0000048)
#define SERIAL_PORT (DEVICE_BASE + 0x00003f8)
#define UART_BASE 0x10000000

static uint32_t image[128] = {
    0x00200113, // addi x2 x0 2
    0x00300193, // addi x3 x0 3
    0x00400213, // addi x4 x0 4
    0x00700393, // addi x7 x0 7
    0x00500413, // addi x8 x0 5
    0x002183B3, // add x7 x3 x2
    0x00720233, // add x4 x4 x7
    0x00338133, // add x2 x7 x3
    0xFFF10193, // addi x3 x2 -1
    0xFFF40413, // addi x8 x8 -1
    0xFE0416E3, // bne x8 x0 -20
    0x800002B7, // lui x5 524288
    0x00028293, // addi x5 x5 0
    0x0002A303, // lw x6 0(x5)
    0x0042A383, // lw x7 4(x5)
    0x0082AE03, // lw x28 8(x5)
    0x00C2AE83, // lw x29 12(x5)
    0x300012F3, // csrrw x5 768 x0
    0x00100073, // ebreak
                // 0x00000297, // auipc t0,0
                // 0x00028823, // sb  zero,16(t0)
                // 0x0102c503, // lbu a0,16(t0)
                // 0x00100073, // ebreak (used as nemu_trap)
                // 0xdeadbeef, // some data
};

bool access_device = false;
uint8_t mem[MEM_SIZE];
uint8_t sram[SRAM_SIZE];
uint8_t mrom[MROM_SIZE];
uint8_t flash[FLASH_SIZE];
uint8_t psram[PSRAM_SIZE];
uint8_t sdram[SDRAM_SIZE];
#define ADDR_MASK (~0x3u)
#define BYTE_MASK (0xFFu)

static bool in_pmem(paddr_t addr) { return addr - MEM_BASE < MEM_SIZE; }
static bool in_clock(paddr_t addr) {
  return addr == RTC_ADDR || addr == RTC_ADDR + 4;
}
static bool in_serial(paddr_t addr) { return addr == SERIAL_PORT; }

uint8_t uart_io_handler(uint32_t offset, int len, uint8_t wdata, bool is_write);
bool in_uart(uint32_t addr);

static uint8_t *guest_to_host(paddr_t addr) { return mem + addr - MEM_BASE; }
static mem_word_t clock_read(paddr_t offset) {
  assert(offset == 0 || offset == 4);
  static uint64_t boot_time = 0;

  struct timespec now;
  clock_gettime(CLOCK_MONOTONIC_COARSE, &now);
  uint64_t now_time = now.tv_sec * 1000000 + now.tv_nsec / 1000;

  if (boot_time == 0) {
    boot_time = now_time;
  }
  now_time -= boot_time;

  return now_time;
}
void serial_write(paddr_t offset, mem_word_t wdata) {
  assert(offset == 0);
  putc(wdata & BYTE_MASK, stderr);
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
#ifdef NPC
    assert(fread(mem, 1, size, fd) == size);
#else
    // Log("Loading %d bytes to MROM\n", size);
    // assert(fread(mrom, 1, size, fd) == size);
    Log("Loading %ld bytes to Flash\n", size);
    assert(fread(flash, 1, size, fd) == size);
#endif
  } else {
#ifdef NPC
    memcpy(mem, image, sizeof(image));
#else
    memcpy(sram, image, sizeof(image));
#endif
  }
}

mem_word_t host_read(uint8_t *addr) {
  auto retval = *(mem_word_t *)(addr);
  return retval;
}
void host_write(uint8_t *addr, mem_word_t wdata, unsigned char wmask) {
  uint8_t *data = (uint8_t *)&wdata;
  for (int i = 0; i < 4; ++i) {
    if ((wmask >> i) & 1) {
      addr[i] = data[i];
    }
  }
}

extern "C" {
mem_word_t mem_read(paddr_t addr) {
#ifdef MTRACE
  if (begin_wave) {
    log_write("(%lu)read:  0x%08x : ", eval_time, addr);
  }
#endif
  auto raw_addr = addr;
  addr &= ADDR_MASK;
  bool valid = false;
  mem_word_t retval = 0;
  if (in_pmem(addr)) {
    valid = true;
    retval = host_read(guest_to_host(addr));
  }
  if (in_clock(addr)) {
    access_device = true;
#ifdef MTRACE
    log_write("|clock| ");
#endif
    valid = true;
    retval = clock_read(addr - RTC_ADDR);
  }
  if (in_uart(raw_addr)) {
    access_device = true;
    valid = true;
    retval = uart_io_handler(raw_addr - UART_BASE, 1, 0, false);
    retval <<= (raw_addr - addr) * 8;
  }
#ifdef MTRACE
  if (begin_wave) {
    if (valid)
      log_write("<0x%08x / %lu>\n", retval, retval);
    else
      log_write("NOT VALID / NOT VALID\n");
  }
#endif
  if (valid) {
    return retval;
  }
  if (running) {
    running = false;
    Log("Invalid read to 0x%08x\n", raw_addr);
  }
  return 0;
}

void mem_write(paddr_t addr, mem_word_t wdata, unsigned char wmask) {
  if (!running)
    return;
  auto raw_addr = addr;
  addr &= ADDR_MASK;
#ifdef MTRACE
  if (begin_wave) {
    log_write("(%lu)write: 0x%08x - %x : 0x%08x / %lu\n", eval_time, addr,
              wmask, wdata, wdata);
  }
#endif
  if (in_pmem(addr)) {
    host_write(guest_to_host(addr), wdata, wmask);
    return;
  }
  if (in_serial(addr) && wmask == 1) {
    access_device = true;
#ifdef MTRACE
    log_write("|serial|\n");
#endif
    serial_write(addr - SERIAL_PORT, wdata);
    return;
  }
  if (in_uart(raw_addr)) {
    access_device = true;
    wdata >>= (raw_addr - addr) * 8;
    uart_io_handler(raw_addr - UART_BASE, 1, (uint8_t)wdata, true);
    return;
  }
  Log("Invalid write to 0x%08x\n", raw_addr);
  running = false;
}

mem_word_t inst_fetch(paddr_t pc) { return mem_read(pc); }

void raise_ebreak() { running = 0; }

void raise_invalid_inst() {
  Log("Invalid instruction\n");
  assert(0);
}

void raise_access_fault() {
  Log("Access fault\n");
  assert(0);
}

void flash_read(int32_t addr, int32_t *data) {
  addr &= ~(0x3u);
  *data = *(int32_t *)(flash + addr);
#ifdef MTRACE
  log_write("(%lu)flash_read: 0x%08x : ", eval_time, FLASH_BASE + addr);
  log_write("0x%08x / %u\n", *data, *data);
#endif
}

void mrom_read(int32_t addr, int32_t *data) {
  addr &= ~(0x3u);
  *data = *(int32_t *)(mrom + addr - MROM_BASE);
#ifdef MTRACE
  log_write("(%lu)mrom_read: 0x%08x : ", eval_time, addr);
  log_write("0x%08x / %u\n", *data, *data);
#endif
}

void psram_read(int32_t addr, int32_t *data) {
  addr &= ~(0x3u);
  *data = *(int32_t *)(psram + addr);
#ifdef MTRACE
  log_write("(%lu)psram_read: 0x%08x : ", eval_time, PSRAM_BASE + addr);
  log_write("0x%08x / %u\n", *data, *data);
#endif
}

void psram_write(int32_t addr, int8_t data) {
  *(int8_t *)(psram + addr) = data;
#ifdef MTRACE
  log_write("(%lu)psram_write: 0x%08x : ", eval_time, PSRAM_BASE + addr);
  log_write("0x%02x / %u\n", data, data);
#endif
}

void sdram_read(uint32_t addr, uint16_t *data) {
  *data = *(uint16_t *)(sdram + addr);
#ifdef MTRACE
  log_write("(%lu)sdram_read: 0x%08x : ", eval_time, SDRAM_BASE + addr);
  log_write("0x%04x / %u\n", *data, *data);
#endif
}

void sdram_write(uint32_t addr, int8_t dqm, int16_t data) {
  for (int i = 0; i < 2; ++i) {
    if (!((dqm >> i) & 1)) {
      uint8_t byte_data = (data >> (i * 8)) & 0xFF;
      *(uint8_t *)(sdram + addr + i) = byte_data;
#ifdef MTRACE
      log_write("(%lu)sdram_write: 0x%08x : ", eval_time,
                SDRAM_BASE + addr + i);
      log_write("0x%02x / %u\n", byte_data, byte_data);
#endif
    }
  }
}
}