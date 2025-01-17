#include "checkpoint.h"

reset_vector:
  la t0, reset_vector
  la t1, CTRL_OFFSET 
  add t1, t0, t1 // read control from RESER_VECTOR + CTRL_OFFSET
  lw t1, 0(t1)
  bnez t1, restore_cpt // if control is 0, do not restore checkpoint
  li t1, 0x80100000
  jr t1
restore_cpt:



// restore CSR
  la t1, CSR_REG_OFFSET
  add t1, t0, t1
  lw t2, 0(t1)
  csrw stvec, t2
  lw t2, 4(t1)
  csrw sscratch, t2
  lw t2, 8(t1)
  csrw sepc, t2
  lw t2, 12(t1)
  csrw scause, t2
  lw t2, 16(t1)
  csrw stval, t2
  lw t2, 20(t1)
  csrw satp, t2
  lw t2, 24(t1)
  csrw mstatus, t2
  lw t2, 28(t1)
  csrw medeleg, t2
  lw t2, 32(t1)
  csrw mideleg, t2
  lw t2, 36(t1)
  csrw mie, t2
  lw t2, 40(t1)
  csrw mtvec, t2
  lw t2, 44(t1)
  csrw menvcfg, t2
  lw t2, 48(t1)
  csrw mscratch, t2
  lw t2, 52(t1)
  csrw mepc, t2
  lw t2, 56(t1)
  csrw mcause, t2
  lw t2, 60(t1)
  csrw mtval, t2
  lw t2, 64(t1)
  csrw mip, t2

// restore MMIO
// mtime
  li t1, MMIO_REG_OFFSET
  add t1, t0, t1

  li t2, CLINT_BASE + MTIME_OFFSET  
  lw t3, 0(t1)
  sw t3, 0(t2)

  lw t3, 4(t1)
  sw t3, 4(t2)

  li t2, CLINT_BASE + MTIMECMP_OFFSET
  lw t3, 8(t1)
  sw t3, 0(t2)
  
  lw t3, 12(t1)
  sw t3, 4(t2)

// restore int registers
restore_int_vector:
  li sp, INT_REG_OFFSET # load int section addr
  add sp, t0, sp # load int section addr
  lw x1, (1*4)(sp)
  lw x3, (3*4)(sp)
  lw x4, (4*4)(sp)
  lw x5, (5*4)(sp)
  lw x6, (6*4)(sp)
  lw x7, (7*4)(sp)
  lw x8, (8*4)(sp)
  lw x9, (9*4)(sp)
  lw x10, (10*4)(sp)
  lw x11, (11*4)(sp)
  lw x12, (12*4)(sp)
  lw x13, (13*4)(sp)
  lw x14, (14*4)(sp)
  lw x15, (15*4)(sp)
  lw x16, (16*4)(sp)
  lw x17, (17*4)(sp)
  lw x18, (18*4)(sp)
  lw x19, (19*4)(sp)
  lw x20, (20*4)(sp)
  lw x21, (21*4)(sp)
  lw x22, (22*4)(sp)
  lw x23, (23*4)(sp)
  lw x24, (24*4)(sp)
  lw x25, (25*4)(sp)
  lw x26, (26*4)(sp)
  lw x27, (27*4)(sp)
  lw x28, (28*4)(sp)
  lw x29, (29*4)(sp)
  lw x30, (30*4)(sp)
  lw x31, (31*4)(sp)
  lw sp, (2*4)(sp)

mret
