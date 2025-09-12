#include <common.h>
#include <isa-def.h>
#include "local-include/reg.h"
#include <isa.h>
#include <cpu/cpu.h>
#include <stdint.h>

static word_t mvenderid = 0x79737978;
static word_t marchid = 23060238;
static word_t mimpid = 0;
static word_t temp;

word_t read_time();

word_t read_timeh();

void difftest_skip_ref();

int csr_priv_ro_check(word_t csr_addr, uint32_t write) {
  // csr attribute

  bool csr_read_only = BITS(csr_addr, 11, 10) == 3;
  int csr_priv = BITS(csr_addr, 9, 8);
  
  // operation write
  bool priv_error = csr_priv > cpu.priv;
  bool ro_error = write && csr_read_only;
  if(priv_error || ro_error ) {
    isa_set_trap(ILLEGAL_INST, current_inst);
    return -1;
  } 

  return 0;
}

// get the underlying CSR
int get_csr(word_t csr_addr, word_t **csr) {  
  // successful access   ->  0
  // exception triggered -> -1
  int succ = 0;

  // stub csr
  temp = 0;
  *csr = &temp;

  if ((csr_addr >= 0xB03 && csr_addr <= 0xB1F) /* mhpmcounter3-31 */ ||
      (csr_addr >= 0xB83 && csr_addr <= 0xB9F) /* mhpmcounterh3-31 */ ||
      csr_addr == 0x320 /* mcountinhibit */ ||
      (csr_addr >= 0x323 && csr_addr <= 0x33F) /* mhpmevent3-31 */ ||
      (csr_addr >= 0x723 && csr_addr <= 0x73F) /* mhpmeventh3-31 */ ) {
    return 0;
  }

  switch (csr_addr) {
  case CSR_SSTATUS:
    *csr = &cpu.mstatus.val;
    break;  
  case CSR_SIE:
    *csr = &cpu.mie.val;
    break;    
  case CSR_STVEC:
    *csr = &cpu.stvec;
    break;
  case CSR_SCOUNTEREN: // scounteren   
    *csr = &temp;
    break;
  case CSR_SSCRATCH:
    *csr = &cpu.sscratch;
    break;
  case CSR_SEPC:
    *csr = &cpu.sepc;
    break;
  case CSR_SCAUSE:
    *csr = &cpu.scause;
    break;
  case CSR_STVAL:
    *csr = &cpu.stval;
    break;
  case CSR_SIP:
    *csr = &cpu.mip.val;
    break;
  case CSR_SATP:
    // printf("satp:   0x%x\n", cpu.satp);
    *csr = &cpu.satp;
    break;
  
  case CSR_MSTATUS: // mstatus
    *csr = &cpu.mstatus.val;
    break;
  case CSR_MISA: // misa
    *csr = &cpu.misa;
    break;
  case CSR_MEDELEG: // medeleg  
    *csr = &cpu.medeleg;
    break;
  case CSR_MIDELEG: // mideleg
    *csr = &cpu.mideleg;
    break;
  case CSR_MIE: // mie
    *csr = &cpu.mie.val;
    break;
  case CSR_MTVEC: // mtvec
    *csr = &cpu.mtvec;
    break;
  case CSR_MCOUNTEREN: // mcounteren
    *csr = &temp;
    break;
  case CSR_MENVCFG:
    *csr = &cpu.menvcfg;
    break;
  case CSR_MSTATUSH:
    *csr = &cpu.mstatush;
    break;
  case CSR_MENVCFGH:
    *csr = &cpu.menvcfgh;
    break;
  case CSR_MSCRATCH: // mscratch
    *csr = &cpu.mscratch;
    break;
  case CSR_MEPC: // mepc
    *csr = &cpu.mepc;
    break;
  case CSR_MCAUSE: // mcause
    *csr = &cpu.mcause;
    break;
  case CSR_MTVAL: // mtval
    *csr = &cpu.mtval;
    break;
  case CSR_MIP: // mip
    *csr = &cpu.mip.val;
    break;
  
  case CSR_TIME:
    temp = read_time();
    *csr = &temp;
    break;
  case CSR_TIMEH:
    temp = read_timeh();
    *csr = &temp;
    break;  

  case CSR_MVENDORID: // mvendorid
    // difftest_skip_ref();
    *csr = &mvenderid;
    break;
  case CSR_MARCHID: // marchid
    // difftest_skip_ref();
    *csr = &marchid;
    break;
  case CSR_MIMPID: // mimpid
    // difftest_skip_ref();
    *csr = &mimpid;
    break;
  case CSR_MHARTID: // mhartid
    *csr = &cpu.mhartid;
    break;

  default:
    // Log("Invalid csr address: 0x%x", csr_addr);
    succ = -1;
    // illegal instruction, it is interesting that:
    // https://github.com/riscv/riscv-isa-manual/issues/1116
    isa_set_trap(ILLEGAL_INST, current_inst);
    // difftest_skip_ref();
    break;
  }
  return succ;
}

// apply read mask 
word_t csr_read(word_t csr_addr, word_t *csr)
{
  switch (csr_addr)
  {
  case CSR_SSTATUS:
    return *csr & CSR_SSTATUS_RMASK;
    break;

  // SIE SIP MIDELEG MEDELEG

  default:
    return *csr;
    break;
  }
}

// write to CSR, with a write mask applied
void write_with_mask(word_t *csr, word_t value, word_t mask) 
{
  *csr = (value & mask) | (*csr & ~mask);
}


void xstatus_update_SD(word_t* csr)
{
  Mstatus *mstatus = (Mstatus*)csr;
  word_t SD = mstatus->XS || mstatus->FS || mstatus->VS;
  write_with_mask(csr, SD << 31, 1 << 31);
}

// apply write mask 
void csr_write(word_t csr_addr, word_t value, word_t *csr)
{
  // int prev = 0;
  // int now = 0;
  // prev = *csr & MASK(14, 13);
  // now = value & MASK(14, 13);
  switch (csr_addr)
  {
  case CSR_MSTATUS:
    // if(!prev && now) Log("mstatus FP on  -- 0x%x",cpu.pc);
    // if(prev && !now) Log("mstatus FP off -- 0x%x",cpu.pc);
    // printf("mstatus before: %x\n",*csr);
    // printf("mstatus mask:   %llx\n",CSR_MSTATUS_WMASK);
    write_with_mask(csr, value, CSR_MSTATUS_WMASK);
    // printf("mstatus after:  %x\n",*csr);
    xstatus_update_SD(csr);
    break;
  case CSR_SSTATUS:
    // if(!prev && now) Log("sstatus FP on  -- 0x%x",cpu.pc);
    // if(prev && !now) Log("sstatus FP off -- 0x%x",cpu.pc);
    write_with_mask(csr, value, CSR_SSTATUS_WMASK);
    xstatus_update_SD(csr);
    break;
  
  case CSR_MENVCFG:
    write_with_mask(csr, value, CSR_MENVCFG_WMASK);
    break;    
  case CSR_MENVCFGH:
    write_with_mask(csr, value, CSR_MENVCFGH_WMASK);
    break;
  
  case CSR_MIDELEG:
    write_with_mask(csr, value, 0x222);
    break;

  case CSR_MEDELEG:
    write_with_mask(csr, value, 0xb7ff);
    break;

  default:
    *csr = value;
    break;
  }
}


// [csrrw/csrrwi] will not read CSR when rd == 0
// [csrrs/csrrc/csrrsi/csrrci] will always read CSR, but will not write CSR when rs1 == 0; 
// Note that if rs1 specifies a register other than x0, and that register holds a zero value, 
// the instruction will not action any attendant per-field side effects, 
// but will action any side effects caused by writing to the entire CSR.

word_t csrr_(word_t csr_addr, uint32_t rd, uint32_t rs1, word_t value, CSR_OP op) {

  bool read = !((op == CSRRW || op == CSRRWI) && rd == 0);
  bool write = !((op == CSRRC && rs1 == 0) || (op == CSRRS && rs1 == 0) || (op == CSRRCI && value == 0) || (op == CSRRSI && value == 0));

  if (csr_priv_ro_check(csr_addr, write)) {
    // priv check failed or writing a read-only register
    return 0;
  }

  word_t *csr;
  if(get_csr(csr_addr, &csr)){
    // csr not found;
    return 0;
  }

  word_t csr_read_val = 0;
  if (read) {
    csr_read_val = csr_read(csr_addr, csr);
  }

  word_t csr_write_val = value;
  switch (op) {
  case CSRRW:
  case CSRRWI:
    csr_write_val = value;
    break;
  case CSRRS:
  case CSRRSI:
    csr_write_val = csr_read_val | value;
    break;
  case CSRRC:
  case CSRRCI:
    csr_write_val = csr_read_val & ~value;
    break;

  default:
    panic("Unknown op: %d\n", op);
    break;
  }
  if (csr_addr == CSR_MSCRATCH) {
    Log("mscratch: 0x%x", csr_write_val);
  }
  if (write) {
    csr_write(csr_addr, csr_write_val, csr);
  }

  return csr_read_val;
}
