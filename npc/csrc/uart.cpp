/***************************************************************************************
 * Copyright (c) 2014-2022 Zihao Yu, Nanjing University
 *
 * NEMU is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan
 *PSL v2. You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY
 *KIND, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 *NON-INFRINGEMENT, MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 *
 * See the Mulan PSL v2 for more details.
 ***************************************************************************************/

#include "cpu.hpp"
#include <cstdint>
#include <cstdlib>
#include <debug.hpp>
#include <signal.h>
#include <stdio.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <unistd.h>

#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>

/* http://en.wikibooks.org/wiki/Serial_Programming/8250_UART_Programming */
// NOTE: this is compatible to 16550

static uint8_t uart_base[9];

#define UART_TX 0
#define UART_RX 0
#define UART_DL1 0
#define UART_IER 1
#define UART_ISR 2
#define UART_LC 3
#define UART_LC_DL_FLAG (1 << 7)
#define UART_LS 5
#define UART_SCR 7
#define UART_LS_DR_FLAG (1 << 0)
#define UART_LS_TFE_FLAG (1 << 5)
#define UART_LS_TE_FLAG (1 << 6)

#define LSR_RX_READY 0x01

#define UART_DL_ACCESS (uart_base[UART_LC] & UART_LC_DL_FLAG)

static void uart_putc(char ch) { putc(ch, stderr); }

#define KEY_QUEUE_LEN 1024
static char key_queue[KEY_QUEUE_LEN] = {};
static int key_f = 0, key_r = 0;

static void uart_key_enqueue(uint32_t keycode) {
  key_queue[key_r] = keycode;
  key_r = (key_r + 1) % KEY_QUEUE_LEN;
  Assert(key_r != key_f, "UART key queue overflow!");
}

static bool uart_key_ready() {
  uint32_t in_buffer = 0;
  ioctl(fileno(stdin), FIONREAD, &in_buffer);
  return in_buffer;
}

static char uart_read() {
  if (!uart_key_ready())
    return 0;

  char read_char;
  bool succ = read(fileno(stdin), &read_char, 1) > 0;
  // Log("Uart get: %d",read_char);
  return succ ? read_char : 0; // when will read(stdin) return -1?
}

static void send_key_uart(char keycode) {
  if (keycode != '\0') {
    uart_key_enqueue(keycode);
  }
}

#define QUEUE_SIZE 1024
static char queue[QUEUE_SIZE] = {};
static int f = 0, r = 0;
#define FIFO_PATH "/tmp/npc-serial"
static int fifo_fd = 0;

static void serial_enqueue(char ch) {
  puts("UART enqueue");
  fflush(stdout);
  int next = (r + 1) % QUEUE_SIZE;
  if (next != f) {
    // not full
    queue[r] = ch;
    r = next;
  }
}

static char serial_dequeue() {
  char ch = 0xff;
  if (f != r) {
    ch = queue[f];
    f = (f + 1) % QUEUE_SIZE;
  }
  return ch;
}

void serial_rx_collect() {
  char input[256];
  // First open in read only and read
  int ret = read(fifo_fd, input, 256);
  assert(ret < 256);
  if (ret > 0) {
    int i;
    for (i = 0; i < ret; i++) {
      serial_enqueue(input[i]);
    }
  }
}

static uint8_t serial_rx_ready_flag() { return (f == r ? 0 : LSR_RX_READY); }

uint8_t calculate_isr() {
  bool receive_ready =
      serial_rx_ready_flag() && (bool)(uart_base[UART_IER] & 0x01);
  bool transmit_ready = true && (bool)(uart_base[UART_IER] & 0x02);
  if (receive_ready) {
    return 0x4;
  } else if (transmit_ready) {
    return 0x2;
  } else {
    return 0x1;
  }
}

uint8_t uart_io_handler(uint32_t offset, int len, uint8_t wdata,
                        bool is_write) {
  // log_write("uart offset=%d, len=%d, wdata=%d, is_write=%d\n", offset, len,
  //           wdata, is_write);
  assert(len == 1);
  if (is_write) {
    uart_base[offset] = wdata;
  }
  static int counter = 0;
  switch (offset) {
  /* We bind the serial port with the host stderr in NEMU. */
  case UART_DL1:
    // also UART_TX/RX
    if (is_write) {
      counter++;
      if (!UART_DL_ACCESS) {
        // uart_putc(uart_base[UART_TX]);
        uart_putc(wdata);
      }
    } else {
      uart_base[UART_RX] = serial_dequeue();
    }
    break;
  case UART_IER:
    break;
  case UART_ISR:
    uart_base[UART_ISR] = calculate_isr();
    break;
  case UART_LC:
    // nothing to do here
    break;
  case UART_LS:
    uart_base[UART_LS] =
        UART_LS_TFE_FLAG | UART_LS_TE_FLAG | serial_rx_ready_flag();
    // printf("uart_key_ready: %d\n", uart_key_ready());
    break;
  case UART_SCR:
    // scratch register, do nothing
    break;
  default: {
    uart_base[offset] = 0; /*printf("do not support offset = %d\n", offset);*/
  }
  }
  // log_write("uart_base[%d] = %x\n", offset, uart_base[offset]);
  return uart_base[offset];
}

static struct termios saved_term;

static void ResetKeyboardInput() {
  fprintf(stderr, "Reset Keyboard.");
  tcsetattr(fileno(stdin), TCSANOW, &saved_term);
}

// Override keyboard, so we can capture all keyboard input for the VM.
static void __attribute((unused)) CaptureKeyboardInput() {
  // Hook exit, because we want to re-enable keyboard.
  atexit(ResetKeyboardInput);
  fprintf(stderr, "Captured Keyboard.");
  struct termios term;
  tcgetattr(fileno(stdin), &term);
  saved_term = term;

  cfmakeraw(&term);
  term.c_lflag |= ISIG; // enable Ctrl-C to kill NEMU
  tcsetattr(fileno(stdin), TCSANOW, &term);
}

static void init_fifo() {
  int ret = mkfifo(FIFO_PATH, 0666);
  assert(ret == 0 || errno == EEXIST);
  fifo_fd = open(FIFO_PATH, O_RDONLY | O_NONBLOCK);
  assert(fifo_fd != -1);
}

void init_uart() {
  // CaptureKeyboardInput();
  init_fifo();
}

bool in_uart(uint32_t addr) {
  return addr >= UART_BASE && addr < UART_BASE + 16; // !
}