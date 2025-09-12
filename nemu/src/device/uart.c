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

#include <stdio.h>
#include <utils.h>
#include <device/map.h>
#include <signal.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <unistd.h>

/* http://en.wikibooks.org/wiki/Serial_Programming/8250_UART_Programming */
// NOTE: this is compatible to 16550

static uint8_t *uart_base = NULL;

#define UART_TX  0
#define UART_RX  0
#define UART_DL1 0
#define UART_DL2 1
#define UART_LC 3
#define UART_LC_DL_FLAG (1 << 7)
#define UART_LS 5
#define UART_SCR 7
#define UART_LS_DR_FLAG (1 << 0)
#define UART_LS_TFE_FLAG (1 << 5)
#define UART_LS_TE_FLAG (1 << 6)

#define UART_DL_ACCESS (uart_base[UART_LC] & UART_LC_DL_FLAG)

static void uart_putc(char ch) {
  MUXDEF(CONFIG_TARGET_AM, putch(ch), putc(ch, stderr));
}

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

void send_key_uart(char keycode) {
  if (nemu_state.state == NEMU_RUNNING && keycode != '\0') {
    uart_key_enqueue(keycode);
  }
}

static void uart_io_handler(uint32_t offset, int len, bool is_write) {
  assert(len == 1);
  switch (offset) {
    /* We bind the serial port with the host stderr in NEMU. */
    case UART_DL1: 
      // also UART_TX/RX
      if (is_write) {
        if (!UART_DL_ACCESS)
          uart_putc(uart_base[UART_TX]);
      } else {
        uart_base[UART_RX] = uart_read();
      }        
      break;
    case UART_DL2: 
      uart_base[offset] = 0;
      break;
    case UART_LC:
      // nothing to do here 
      break;
    case UART_LS:
      uart_base[UART_LS] = UART_LS_TFE_FLAG | UART_LS_TE_FLAG | uart_key_ready();
      // printf("uart_key_ready: %d\n", uart_key_ready());
      break;
    case UART_SCR:
      // scratch register, do nothing
      break;
    default: {uart_base[offset] = 0;/*printf("do not support offset = %d\n", offset);*/}
  }
}

static struct termios saved_term;

static void ResetKeyboardInput()
{
  fprintf(stderr, "Reset Keyboard.");
	tcsetattr(fileno(stdin), TCSANOW, &saved_term);
}


// Override keyboard, so we can capture all keyboard input for the VM.
static void __attribute((unused)) CaptureKeyboardInput()
{
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


void init_uart() {

  uart_base = new_space(9);
  memset(uart_base, 0, 9);
  add_mmio_map("uart", CONFIG_UART_ADDR, uart_base, 9, uart_io_handler);
  // CaptureKeyboardInput();
}
