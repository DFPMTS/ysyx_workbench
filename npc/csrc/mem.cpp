#include "mem.hpp"
#include "config.hpp"
#include "cpu.hpp"
#include "debug.hpp"
#include "difftest.hpp"
#include "status.hpp"
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <iostream>
#include <svdpi.h>
#include <sys/types.h>

#define DEVICE_BASE 0xa0000000

#define RTC_ADDR (DEVICE_BASE + 0x0000048)
#define SERIAL_PORT (DEVICE_BASE + 0x00003f8)

static uint32_t image[128] = {
    0x100002B7, 0x00028293, 0x00000513, 0x00828303,
    0x006565B3, 0x00B28223, 0x01050513, 0xFF1FF06F,
};

bool access_device = false;
uint8_t *mem = nullptr;
uint8_t sram[SRAM_SIZE];
uint8_t mrom[MROM_SIZE];
uint8_t flash[FLASH_SIZE];
uint8_t psram[PSRAM_SIZE];
uint8_t sdram[SDRAM_SIZE];
#define ADDR_MASK (~0x3u)
#define BYTE_MASK (0xFFu)

static bool in_pmem(paddr_t addr) {
  // return addr - MEM_BASE < MEM_SIZE;
  return addr < 0xf0000000;
}
static bool in_clock(paddr_t addr) {
  return false;
  return addr == RTC_ADDR || addr == RTC_ADDR + 4;
}
static bool in_serial(paddr_t addr) {
  return false;
  return addr == SERIAL_PORT;
}

bool in_confreg(uint32_t addr) {
  return addr >= CONFGREG_BASE && addr < CONFGREG_BASE + 0x100000;
}

uint8_t uart_io_handler(uint32_t offset, int len, uint8_t wdata, bool is_write);

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
  // * first, allocate mem
  // printf("Allocating %zu bytes for MEM\n", MEM_SIZE);
  mem = new uint8_t[MEM_SIZE];
  assert(mem);
  memset(mem, 0x23, MEM_SIZE);
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
    auto img_name = std::string(img);
    if (img_name.substr(img_name.size() - 5) == ".vlog") {
      // vlog file
      printf("Parsing vlog file: %s\n", img);
      char line[1024];
      uint32_t addr = 0;
      uint32_t bytes = 0;
      while (fgets(line, sizeof(line), fd)) {
        if (line[0] == '@') {
          if (bytes != 0) {
            printf("0x%08x bytes loaded to 0x%08x\n", bytes, addr);
            bytes = 0;
          }
          sscanf(line + 1, "%x", &addr);
          printf("Loading to address 0x%08x\n", addr);
        } else {
          uint8_t val = (uint8_t)strtoul(line, NULL, 16);
          mem[addr + bytes] = val;
          bytes++;
        }
      }
      if (bytes != 0) {
        printf("0x%08x bytes loaded to 0x%08x\n", bytes, addr);
      }
      // * Dump 0x00000000-0x20000000 to file
      FILE *out = fopen("mem_dump.bin", "wb");
      if (out) {
        fwrite(mem, 1, 0x20000000, out);
        fclose(out);
      }
    } else {
      fseek(fd, 0, SEEK_END);
      auto size = ftell(fd);
      fseek(fd, 0, SEEK_SET);
      printf("Loading %ld bytes to MEM\n", size);
#ifdef NPC
      // assert(fread(mem, 1, size, fd) == size);
      auto read_size = fread(mem + RESET_VECTOR, 1, size, fd);
      if (read_size != size) {
        printf("Error reading image, read %ld bytes\n", read_size);
        exit(1);
      }
      fclose(fd);
#else
      // Log("Loading %d bytes to MROM\n", size);
      // assert(fread(mrom, 1, size, fd) == size);
      Log("Loading %ld bytes to Flash\n", size);
      assert(fread(flash, 1, size, fd) == size);
#endif
    }
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
void mem_read(uint32_t en, uint32_t addr, uint32_t *result) {
  if (!en || !running.load()) {
    printf("mem_read: en = %u  running = %u\n", en, running.load());
    return;
  }
  // #ifdef MTRACE
  //   if (begin_wave) {
  //     log_write("(%lu)read:  0x%08x : ", eval_time, addr);
  //   }
  // #endif
  auto raw_addr = addr;
  addr &= ADDR_MASK;
  // if (addr == 0x08032720) {
  //   stop = Stop::DIFFTEST_FAILED;
  //   running.store(false);
  // }
  bool valid = false;
  mem_word_t retval = 0;
  if (in_confreg(raw_addr)) {
    valid = true;
  } else if (in_uart(raw_addr)) {
    // access_device = true;
    valid = true;
    retval = uart_io_handler(raw_addr - UART_BASE, 1, 0, false);
    for (int i = 0; i < 1; ++i) {
      result[i] = 0;
    }
    if (raw_addr - addr < 4) {
      result[0] = retval << (8 * (raw_addr - addr));
    } else if (raw_addr - addr < 8) {
      result[1] = retval << (8 * (raw_addr - addr - 4));
    } else {
      result[2] = retval << (8 * (raw_addr - addr - 8));
    }
  } else if (in_pmem(addr)) {
    valid = true;
    // retval = host_read(guest_to_host(addr));
    if (begin_wave) {
      printf("addr = 0x%08x\n", addr);
    }

    for (int i = 0; i < 1; ++i) {
      result[i] = *((uint32_t *)guest_to_host(addr) + i);
      if (begin_wave) {
        printf("result[%d] = 0x%08x\n", i, result[i]);
      }
    }
  }
  // #ifdef MTRACE
  //   if (begin_wave) {
  //     if (valid)
  //       log_write("<0x%08x / %lu>\n", retval, retval);
  //     else
  //       log_write("NOT VALID / NOT VALID\n");
  //   }
  // #endif
  if (valid) {

  } else {
    printf("Invalid read to 0x%08x\n", raw_addr);
    running.store(false);
    stop = Stop::DIFFTEST_FAILED;
    for (int i = 0; i < 1; ++i) {
      result[i] = 0x57575757;
    }
  }
  // if (running.load()) {
  //   running.store(false);
  //   Log("Invalid read to 0x%08x\n", raw_addr);
  // }
}

void check_memory(paddr_t addr, size_t n) {
  static uint32_t buf[16];
  ref_difftest_memcpy(addr, buf, 4 * n, DIFFTEST_TO_DUT);
  for (int i = 0; i < n; ++i) {
    if (buf[i] != *(uint32_t *)(guest_to_host(addr) + i * 4)) {
      Log("Memory mismatch at %08x: ref = %08x, dut = %08x\n", addr + i * 4,
          buf[i], *(uint32_t *)(guest_to_host(addr) + i * 4));
      stop = Stop::DIFFTEST_FAILED;
      running.store(false);
    }
  }
}

void mem_write(uint32_t en, uint32_t addr, uint32_t wdata, uint32_t wmask) {
  if (!en || !running.load()) {
    printf("mem_write: en = %u  running = %u\n", en, running.load());
    return;
  }
  auto raw_addr = addr;
  addr &= ADDR_MASK;
  // #ifdef MTRACE
  //   if (begin_wave) {
  //     log_write("(%lu)write: 0x%08x - %x : 0x%08x / %lu\n", eval_time, addr,
  //               wmask, wdata, wdata);
  //   }
  // #endif
  auto wdata_ptr = (uint8_t *)&wdata;
  if (in_confreg(raw_addr)) {
    return;
  } else if (in_uart(raw_addr)) {
    // access_device = true;
    uart_io_handler(raw_addr - UART_BASE, 1, wdata_ptr[raw_addr - addr], true);
    return;
  } else if (in_pmem(addr)) {
    if (begin_wave) {
      printf("addr = 0x%08x\n", addr);
      printf("wmask = 0x%08x\n", wmask);
      for (int i = 0; i < 1; ++i) {
        printf("wdata[%d] = 0x%08x\n", i, wdata);
      }
    }
    // host_write(guest_to_host(addr), wdata, wmask);
    for (int i = 0; i < 4; ++i) {
      if ((wmask >> i) & 1) {
        *((uint8_t *)guest_to_host(addr) + i) = wdata_ptr[i];
      }
    }

    // if (wmask == 0xFFFFFFFF) {
    //   check_memory(addr, 8);
    // }

    return;
  }
  //   if (in_serial(addr) && wmask == 1) {
  //     access_device = true;
  // #ifdef MTRACE
  //     log_write("|serial|\n");
  // #endif
  //     serial_write(addr - SERIAL_PORT, wdata);
  //     return;
  //   }
  Log("Invalid write to 0x%08x\n", raw_addr);
  running.store(false);
  stop = Stop::DIFFTEST_FAILED;
}

// mem_word_t inst_fetch(paddr_t pc) { return mem_read(pc); }

void raise_ebreak() { running.store(false); }

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