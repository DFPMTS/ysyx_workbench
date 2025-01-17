/***************************************************************************************
* Copyright (c) 2014-2022 Zihao Yu, Nanjing University
*
* NEMU is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

#include <device/map.h>
#include <device/alarm.h>
#include <stdint.h>
#include <sys/types.h>
#include <utils.h>

static uint32_t *mtime_port_base = NULL;
static uint32_t *mtimecmp_port_base = NULL;
static uint32_t *msip_port_base = NULL;

static void mtime_io_handler(uint32_t offset, int len, bool is_write) {
  assert(offset == 0 || offset == 4);  
  // if(!is_write){
    // uint64_t us = get_time();    
    // if (offset == 0) mtime_port_base[0] = (uint32_t)us;
    // if (offset == 4) mtime_port_base[1] = us >> 32;
    // printf("read mtime offset=%d, %lu\n",offset, *(uint64_t*)mtime_port_base);
  // }
}

static void mtimecmp_io_handler(uint32_t offset, int len, bool is_write) {
  assert(offset == 0 || offset == 4);    
  // printf("mtimecmp: %lu\n",*(uint64_t*)mtimecmp_port_base);
}

static void msip_io_handler(uint32_t offset, int len, bool is_write) {
  // high 31 bits hardwired to zero
  if(is_write) {
    msip_port_base[0] &= 1;
  }
}

#define CYCLE (*(uint64_t*)mtime_port_base)

uint64_t read_mtime()  {
  return CYCLE;
  // return get_time();
}

word_t read_time()  {
  return CYCLE;
  // return get_time();
}

word_t read_timeh()  {
  return CYCLE >> (sizeof(word_t) * 8);
  // return get_time() >> (sizeof(word_t) * 8);
}

void cycle_mtime()
{
  (*(uint64_t*)mtime_port_base)+=10;
}

uint64_t read_mtimecmp() {
  return *(uint64_t*)mtimecmp_port_base;
}

void init_clint() {
  mtime_port_base = (uint32_t *)new_space(8);
  *(uint64_t*)mtime_port_base = 0;
  add_mmio_map("mtime", CONFIG_CLINT_ADDR + 0xbff8, mtime_port_base, 8, mtime_io_handler);

  mtimecmp_port_base = (uint32_t *)new_space(8);
  add_mmio_map("mtimecmp", CONFIG_CLINT_ADDR + 0x4000, mtimecmp_port_base, 8, mtimecmp_io_handler);

  msip_port_base = (uint32_t *)new_space(4);
  msip_port_base[0] = 0;
  add_mmio_map("msip", CONFIG_CLINT_ADDR + 0x0000, msip_port_base, 4, msip_io_handler);
}
