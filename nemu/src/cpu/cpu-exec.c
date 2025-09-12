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

#include "utils.h"
#include <cpu/cpu.h>
#include <cpu/decode.h>
#include <cpu/difftest.h>
#include <locale.h>
#include <signal.h>
#include "common.h"

/* The assembly code of instructions executed is only output to the screen
 * when the number of instructions executed is less than this value.
 * This is useful when you use the `si' command.
 * You can modify this value as you want.
 */
#define MAX_INST_TO_PRINT 10
#define NR_IRINGBUF 16

CPU_state cpu = {};
uint64_t g_nr_guest_inst = 0;
static uint64_t g_timer = 0; // unit: us
static bool g_print_step = false;

static struct iringbuf{
  char logs[NR_IRINGBUF][128];
  int cur;
}iringbuf;

void device_update();
void wp_check(vaddr_t pc);
void iringbuf_log(char *log);
void iringbuf_display();
char *func_sym_search(word_t pc);

static void trace_and_difftest(Decode *_this, vaddr_t dnpc) {
#ifdef CONFIG_ITRACE_COND
  if (ITRACE_COND) { log_write("%s\n", _this->logbuf); }
#endif
  if (g_print_step) { IFDEF(CONFIG_ITRACE, puts(_this->logbuf)); }
  IFDEF(CONFIG_DIFFTEST, difftest_step(_this->pc, dnpc));
  IFDEF(CONFIG_WATCHPOINT, wp_check(_this->pc));
}

static void exec_once(Decode *s, vaddr_t pc) {
  s->pc = pc;
  s->snpc = pc;
  isa_exec_once(s);
  cpu.pc = s->dnpc;
}

static bool need_stop = false;
static void SIGINT_handler(int sig)
{
  need_stop = true;
}

static void execute(uint64_t n) {
  signal(SIGINT, SIGINT_handler);
  Decode s;
  for (;n > 0; n --) {
    exec_once(&s, cpu.pc);
    g_nr_guest_inst ++;
    trace_and_difftest(&s, cpu.pc);
    if (nemu_state.state != NEMU_RUNNING) break;
    IFDEF(CONFIG_DEVICE, device_update());
    if(need_stop) {
      set_nemu_state(NEMU_ABORT, cpu.pc, -1);
      break;
    }
  }
}

void statistic() {
  isa_reg_display();
  iringbuf_display();
  IFNDEF(CONFIG_TARGET_AM, setlocale(LC_NUMERIC, ""));
#define NUMBERIC_FMT MUXDEF(CONFIG_TARGET_AM, "%", "%'") PRIu64
  Log("host time spent = " NUMBERIC_FMT " us", g_timer);
  Log("total guest instructions = " NUMBERIC_FMT, g_nr_guest_inst);
  if (g_timer > 0) Log("simulation frequency = " NUMBERIC_FMT " inst/s", g_nr_guest_inst * 1000000 / g_timer);
  else Log("Finish running in less than 1 us and can not calculate the simulation frequency");  
}

void assert_fail_msg() {
  isa_reg_display();
  statistic();
  iringbuf_display();
}

/* Simulate how the CPU works. */
void cpu_exec(uint64_t n) {
  g_print_step = (n < MAX_INST_TO_PRINT);
  switch (nemu_state.state) {
    case NEMU_END: case NEMU_ABORT:
      printf("Program execution has ended. To restart the program, exit NEMU and run again.\n");
      return;
    default: nemu_state.state = NEMU_RUNNING;
  }

  uint64_t timer_start = get_time();

  execute(n);

  uint64_t timer_end = get_time();
  g_timer += timer_end - timer_start;

  switch (nemu_state.state) {
    case NEMU_RUNNING: nemu_state.state = NEMU_STOP; break;

    case NEMU_END: case NEMU_ABORT:
      Log("Priv: %d\n",cpu.priv);
      Log("nemu: %s at pc = " FMT_WORD,
          (nemu_state.state == NEMU_ABORT ? ANSI_FMT("ABORT", ANSI_FG_RED) :
           (nemu_state.halt_ret == 0 ? ANSI_FMT("HIT GOOD TRAP", ANSI_FG_GREEN) :
            ANSI_FMT("HIT BAD TRAP", ANSI_FG_RED))),
          nemu_state.halt_pc);
      // fall through
    case NEMU_QUIT: statistic();
  }
}

// generate and log itrace into iringbuf
void itrace_generate(Decode *s)
{
#ifdef CONFIG_ITRACE
  char *p = s->logbuf;
  p += snprintf(p, sizeof(s->logbuf), FMT_WORD ":", s->pc);
  int ilen = s->snpc - s->pc;
  int i;
  uint8_t *inst = (uint8_t *)&s->isa.inst.val;
  for (i = ilen - 1; i >= 0; i--) {
    p += snprintf(p, 4, " %02x", inst[i]);
  }
  int ilen_max = MUXDEF(CONFIG_ISA_x86, 8, 4);
  int space_len = ilen_max - ilen;
  if (space_len < 0)
    space_len = 0;
  space_len = space_len * 3 + 1;
  memset(p, ' ', space_len);
  p += space_len;

#ifndef CONFIG_ISA_loongarch32r
  void disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte);
  disassemble(p, s->logbuf + sizeof(s->logbuf) - p,
              MUXDEF(CONFIG_ISA_x86, s->snpc, s->pc),
              (uint8_t *)&s->isa.inst.val, ilen);
#else
  p[0] = '\0'; // the upstream llvm does not support loongarch32r
#endif
  iringbuf_log(s->logbuf);
#endif
}

void ftrace_log(Decode *s, int rd, int rs1, word_t offset) {
  /*  only under these circumstances:
      [call]
      jal  x1,      offset
      jalr x1, rs1, offset
      jalr x0, x6,  offset
      [ret]
      jalr x0, x1,  0
  */
#ifdef CONFIG_FTRACE
  static int level = 0;
  static char dst_addr[128];
  
  bool call = (rd == 1) || (rd == 0 && rs1 == 6);
  bool ret = (rd == 0 && rs1 == 1 && offset == 0);

  // ignore non call/ret jal/jalr
  if (!call && !ret)
    return;

  Assert(!(call && ret), "Wrong logic for deciding call/ret");

 /*
  example:
  0x8000016c:                       call [f2@0x800000a4]
  0x800000f0:                         call [f1@0x8000005c]
  0x80000058:                         ret  [f0]
  0x80000100:                       ret  [f2]
  */

  // the @0x80000000 part
  if (call) {
    assert(sprintf(dst_addr, "@" FMT_WORD, s->dnpc));
  } else {
    dst_addr[0] = '\0';
  }

  // for call, func_name is func to jump to; for ret, func_name is func to
  // return from
  char *func_name = call ? func_sym_search(s->dnpc) : func_sym_search(s->pc);

  // indent level
  int indent = call ? (level++) * 2 : (--level) * 2;
  
  log_write(FMT_WORD ": %*s%s [%s%s]\n", s->pc, indent, "",
            call ? "call" : "ret ", func_name, dst_addr);
#endif
}

void init_iringbuf() {
  // set current inst ptr
  iringbuf.cur = 0;
  // set all logs to NA
  for (int i = 0; i < NR_IRINGBUF; ++i) {
    strcpy(iringbuf.logs[i], "NA");
  }
}

void iringbuf_log(char *log) {
  strcpy(iringbuf.logs[iringbuf.cur], log);
  iringbuf.cur++;
  if (iringbuf.cur >= NR_IRINGBUF)
    iringbuf.cur -= NR_IRINGBUF;
}

void iringbuf_display()
{
  int cur_inst = (iringbuf.cur + NR_IRINGBUF - 1) % NR_IRINGBUF;
  for (int i = 0; i < NR_IRINGBUF; ++i) {
    printf("%5s%s\n", (i == cur_inst) ? "--> " : "", iringbuf.logs[i]);
    if (i == cur_inst) {
      puts("-------------------------------------------------------------");
    }
  }
}