#ifndef CONFIG_HPP
#define CONFIG_HPP

#include "config.h"
#include <cstddef>
#include <cstdint>

constexpr size_t MEM_BASE = 0x80000000;
constexpr size_t MEM_SIZE = 0x80000000;

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

using word_t = uint32_t;

#endif