#include "debug.hpp"

#include <cassert>
#include <cstdio>

FILE *log_fd = NULL;

void init_log(const char *log_file) {
  assert(log_file);
  log_fd = fopen(log_file, "w");
  if (!log_fd) {
    printf("Unable to open log file: %s\n", log_file);
    assert(0);
  }
}

void close_log() {
  if (log_fd) {
    fclose(log_fd);
    log_fd = NULL;
  }
}
