#ifndef YSYXSOC_H__
#define YSYXSOC_H__

#include "riscv/riscv.h"

#define UART_BASE 0x10000000
#define UART_TX  0
#define UART_RX  0
#define UART_DL1 0
#define UART_DL2 1
#define UART_LC 3
#define UART_LC_DL_FLAG (1 << 7)
#define UART_LS 5
#define UART_LS_DR_FLAG (1 << 0)
#define UART_LS_TFE_FLAG (1 << 5)

#define INPUT_BASE 0x10011000

#define VGA_BASE 0x21000000

#endif