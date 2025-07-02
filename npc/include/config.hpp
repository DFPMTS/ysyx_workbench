#ifndef CONFIG_HPP
#define CONFIG_HPP

#include "config.h"
#include <cstddef>
#include <cstdint>

constexpr size_t MEM_BASE = 0;
constexpr size_t MEM_SIZE = 0xF0000000;
constexpr size_t RESET_VECTOR = 0x1C000000;

constexpr size_t SRAM_SIZE = 0x00008000;
constexpr size_t SRAM_BASE = 0x08000000;

constexpr size_t MROM_SIZE = 0x00001000;
constexpr size_t MROM_BASE = 0x20000000;

constexpr size_t FLASH_SIZE = 0x00800000;
constexpr size_t FLASH_BASE = 0x30000000;

constexpr size_t PSRAM_BASE = 0x80000000;
constexpr size_t PSRAM_SIZE = 0x00400000;

constexpr size_t SDRAM_BASE = 0xA0000000;
constexpr size_t SDRAM_SIZE = 0x02000000;

constexpr size_t WAIT_INTERVAL = 1;
constexpr size_t SLOT_SIZE = 2;

constexpr size_t FORK_CYCLE = 50000;

constexpr size_t ISSUE_WIDTH = 3;
constexpr size_t MACHINE_WIDTH = 4;
constexpr size_t WRITEBACK_WIDTH = 5;
constexpr size_t COMMIT_WIDTH = 3;

constexpr size_t NUM_PREG = 64;
constexpr size_t ROB_SIZE = 32;

constexpr size_t CLINT_BASE = 0x0b000000;
constexpr size_t UART_BASE = 0x0a000000;

using word_t = uint32_t;

#endif