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

#include <isa.h>
#include <cpu/cpu.h>
#include <readline/readline.h>
#include <readline/history.h>
#include "sdb.h"
#include "memory/vaddr.h"
#include <memory/paddr.h>
#include <checkpoint.h>

static int is_batch_mode = false;

void init_regex();
void init_wp_pool();

/* We use the `readline' library to provide more flexibility to read from stdin. */
static char* rl_gets() {
  static char *line_read = NULL;

  if (line_read) {
    free(line_read);
    line_read = NULL;
  }

  line_read = readline("(nemu) ");

  if (line_read && *line_read) {
    add_history(line_read);
  }

  return line_read;
}

static int cmd_c(char *args) {
  cpu_exec(-1);
  return 0;
}


static int cmd_q(char *args) {
  nemu_state.state = NEMU_QUIT;
  return -1;
}

static int cmd_help(char *args);

static int cmd_si(char *args);

static int cmd_info(char *args);

static int cmd_x(char *args);

static int cmd_p(char *args);

static int cmd_w(char *args);

static int cmd_d(char *args);

static int cmd_cpt(char *args);

static struct {
  const char *name;
  const char *description;
  int (*handler) (char *);
} cmd_table [] = {
  { "help", "Display information about all supported commands", cmd_help },
  { "c", "Continue the execution of the program", cmd_c },
  { "q", "Exit NEMU", cmd_q },
  { "si", "Continue the execution of the program for N steps. When not given, N is default to 1.", cmd_si},
  { "info", "print information about registers or watchpoints.", cmd_info},
  { "p", "Evaluate expression.", cmd_p},
  { "x", "Examine memory.", cmd_x},
  { "w", "Set watch point.", cmd_w},
  { "d", "Delete watch point", cmd_d},
  { "cpt", "Save checkpoint", cmd_cpt},

  /* TODO: Add more commands */

};

#define NR_CMD ARRLEN(cmd_table)

static int cmd_help(char *args) {
  /* extract the first argument */
  char *arg = strtok(NULL, " ");
  int i;

  if (arg == NULL) {
    /* no argument given */
    for (i = 0; i < NR_CMD; i ++) {
      printf("%s - %s\n", cmd_table[i].name, cmd_table[i].description);
    }
  }
  else {
    for (i = 0; i < NR_CMD; i ++) {
      if (strcmp(arg, cmd_table[i].name) == 0) {
        printf("%s - %s\n", cmd_table[i].name, cmd_table[i].description);
        return 0;
      }
    }
    printf("Unknown command '%s'\n", arg);
  }
  return 0;
}

static int cmd_si(char *args) {
  /* extract the first argument */
  char *arg = strtok(NULL, " ");

  if (arg == NULL) {
    // default value is 1
    cpu_exec(1);
  } else {
    // read argument
    long long num_steps = atoll(args);

    if (num_steps <= 0) {
      // num_steps <= 0?
      printf("N have to a positive number\n");
    } else {
      // implicit conversion is ok here
      cpu_exec(num_steps);
    }
  }

  return 0;
}

static int cmd_info(char *args) {
  /* extract the first argument: r/w */
  char *arg = strtok(NULL, " ");

  if (arg == NULL) {
    // need argument
    printf("Need argument: r(registers) or w(watchpoints)\n");
  } else {
    if (strcmp("r", arg) == 0) {
      // print registers
      isa_reg_display();
    } else if (strcmp("w", arg) == 0) {
      // print watch points
      wp_display();
    }
  }

  return 0;
}

static int cmd_x(char *args) {
  // extract the first argument: N
  char *arg = strtok(NULL, " ");

  if (arg == NULL) {
    // need argument
    printf("Format: x N [expr]\n");
  } else {
    int N = atoi(arg);
    if (N > 0) {
      // extract the second argument.
      arg = strtok(NULL, "");
      if (arg == NULL) {
        printf("Need expression.\n");
      } else {
        bool success = true;
        vaddr_t addr = expr(arg, &success);
        if (success) {
          for (int i = 0; i < N; ++i) {
            printf("0x%08X\n", vaddr_read(addr + 4 * i, 4));
          }
        } else {
          printf("[expr] has to be a valid address.\n");
        }
      }
    } else {
      printf("N has to be a positive number.\n");
    }
  }

  return 0;
}

static int cmd_p(char *args) {
  // the rest of line is the first argument: [expr]
  char *arg = args;

  if (arg == NULL) {
    // need argument
    printf("Format: p [expr]\n");
  } else {
    bool success = true;
    word_t value = expr(arg, &success);
    if(success){
      printf("%u\n",value);
    }else{
      printf("[expr] not valid!\n");
    }
  }

  return 0;
}

static int cmd_w(char *args) {
   // the rest of line is the first argument: [expr]
  char *arg = args;

  if (arg == NULL) {
    // need argument
    printf("Format: w [expr]\n");
  } else {
    wp_add(arg);
  }

  return 0;
}

static int cmd_d(char *args) {
  // extract the first argument: watch point NO
  char *arg = strtok(NULL, " ");

  if (arg == NULL) {
    // need argument
    printf("Format: d NO\n");
  } else {
    int NO = atoi(arg);
    wp_delete(NO);
  }

  return 0;
}

uint8_t* guest_to_host(paddr_t paddr);
uint32_t read_time();
uint32_t read_timeh();
uint64_t read_mtimecmp();

static void save_checkpoint() {

  printf("Save checkpoint:\n");
  isa_reg_display();

  // * pc -> mepc, priv -> mstatus.MPP
  cpu.mepc = cpu.pc;
  cpu.mstatus.MPP = cpu.priv;
  cpu.mstatus.MPIE = cpu.mstatus.MIE;
  cpu.mstatus.MIE = 0;

  // * save CSR
  paddr_t t1 = RESET_VECTOR + CSR_REG_OFFSET;
  paddr_write(t1, 4, cpu.stvec);
  paddr_write(t1 + 4, 4, cpu.sscratch);
  paddr_write(t1 + 8, 4, cpu.sepc);
  paddr_write(t1 + 12, 4, cpu.scause);
  paddr_write(t1 + 16, 4, cpu.stval);
  paddr_write(t1 + 20, 4, cpu.satp);
  paddr_write(t1 + 24, 4, cpu.mstatus.val);
  paddr_write(t1 + 28, 4, cpu.medeleg);
  paddr_write(t1 + 32, 4, cpu.mideleg);
  paddr_write(t1 + 36, 4, cpu.mie.val);
  paddr_write(t1 + 40, 4, cpu.mtvec);
  paddr_write(t1 + 44, 4, cpu.menvcfg);
  paddr_write(t1 + 48, 4, cpu.mscratch);
  paddr_write(t1 + 52, 4, cpu.mepc);
  paddr_write(t1 + 56, 4, cpu.mcause);
  paddr_write(t1 + 60, 4, cpu.mtval);
  paddr_write(t1 + 64, 4, cpu.mip.val);

  // * save INT
  t1 = RESET_VECTOR + INT_REG_OFFSET;
  for (int i = 0; i < 32; i++) {
    paddr_write(t1 + i * 4, 4, cpu.gpr[i]);
  }

  // * save MMIO (MTIME/MTIMECMP)
  t1 = RESET_VECTOR + MMIO_REG_OFFSET;
  paddr_write(t1, 4, read_time());
  paddr_write(t1 + 4, 4, read_timeh());

  uint64_t mtimecmp = read_mtimecmp();
  paddr_write(t1 + 8, 4, mtimecmp);
  paddr_write(t1 + 12, 4, mtimecmp >> 32);

  // * toggle CTRL
  t1 = RESET_VECTOR + CTRL_OFFSET;
  paddr_write(t1, 4, 1);
}

static int cmd_cpt(char *args) {
  // extract the first argument: file name
  char *arg = strtok(NULL, " ");

  if (arg == NULL) {
    // need argument
    printf("Format: cpt file_name\n");
  } else {
    FILE *fp = fopen(arg, "wb");
    if (fp == NULL) {
      printf("Can not open file %s\n", arg);
    } else {
      save_checkpoint();
      fwrite(guest_to_host(RESET_VECTOR), 1, CONFIG_MSIZE, fp);
      fclose(fp);
    }
  }

  return 0;
}

void sdb_set_batch_mode() {
  is_batch_mode = true;
}

void sdb_mainloop() {
  if (is_batch_mode) {
    cmd_c(NULL);
    return;
  }

  for (char *str; (str = rl_gets()) != NULL; ) {
    char *str_end = str + strlen(str);

    /* extract the first token as the command */
    char *cmd = strtok(str, " ");
    if (cmd == NULL) { continue; }

    /* treat the remaining string as the arguments,
     * which may need further parsing
     */
    char *args = cmd + strlen(cmd) + 1;
    if (args >= str_end) {
      args = NULL;
    }

#ifdef CONFIG_DEVICE
    extern void sdl_clear_event_queue();
    sdl_clear_event_queue();
#endif

    int i;
    for (i = 0; i < NR_CMD; i ++) {
      if (strcmp(cmd, cmd_table[i].name) == 0) {
        if (cmd_table[i].handler(args) < 0) { return; }
        break;
      }
    }

    if (i == NR_CMD) { printf("Unknown command '%s'\n", cmd); }
  }
}

void init_sdb() {
  /* Compile the regular expressions. */
  init_regex();

  /* Initialize the watchpoint pool. */
  init_wp_pool();
}
