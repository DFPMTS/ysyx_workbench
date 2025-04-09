#include "monitor.hpp"
#include "debug.hpp"
#include "difftest.hpp"
#include "disasm.hpp"
#include "func_sym.hpp"
#include "mem.hpp"
#include <cstddef>
#include <cstdio>
#include <getopt.h>

static const char *diff_so_file = NULL;
static const char *log_file = NULL;
static const char *elf_file = NULL;
static const char *img_file = NULL;

void parse_args(int argc, char *argv[]) {
  const struct option table[] = {{"log", required_argument, NULL, 'l'},
                                 {"diff", required_argument, NULL, 'd'},
                                 {"elf", required_argument, NULL, 'e'},
                                 {"help", no_argument, NULL, 'h'},
                                 {0, 0, 0, 0}};
  int o;
  while ((o = getopt_long(argc, argv, "-hl:d:e:", table, NULL)) != -1) {
    switch (o) {
    case 'l':
      log_file = optarg;
      break;

    case 'd':
      diff_so_file = optarg;
      break;

    case 'e':
      elf_file = optarg;
      break;

    case 1:
      img_file = optarg;
      break;

    default:
      printf("Usage: [IMG] -l,--log [LOG] -d,--diff [DIFF_SO] -e,--elf "
             "[ELF]\n");
      break;
    }
  }
  printf("%s\n%s\n%s\n%s\n", log_file, diff_so_file, elf_file, img_file);
}

void init_monitor(int argc, char *argv[]) {
  parse_args(argc, argv);
  init_log(log_file);
  load_img(img_file);
  init_func_sym(elf_file);
  init_cpu();
  init_disasm();
#ifdef DIFFTEST
  init_difftest(diff_so_file);
#endif
}