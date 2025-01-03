#ifndef SIMSTATE_HPP
#define SIMSTATE_HPP

#include "Uop.hpp"
#include "cpu.hpp"
#include "debug.hpp"
#include "difftest.hpp"
#include "itrace.hpp"
#include "mem.hpp"
#include "status.hpp"
#include <cstdint>
#include <cstdio>

class SimState {
public:
  RenameUop renameUop[4];
  WritebackUop writebackUop[4];
  ReadRegUop readRegUop[4];
  CommitUop commitUop[4];
  FlagUop flagUop[1];

  InstInfo insts[128];
  uint32_t archTable[32] = {};
  uint32_t pReg[64] = {};
  uint32_t pc = 0;
  uint32_t lastCommit;
  uint64_t instRetired = 0;

  void bindUops() {
    // * renameUop
#define UOP renameUop
#define V_UOP V_RENAME_UOP
#define V_UOP_VALID V_RENAME_VALID
#define V_UOP_READY V_RENAME_READY
#define UOP_FIELDS RENAME_FIELDS
    REPEAT_1(BIND_FIELDS)
    REPEAT_1(BIND_VALID)
    REPEAT_1(BIND_READY)

    // * readRegUop
#define UOP readRegUop
#define V_UOP V_READREG_UOP
#define V_UOP_VALID V_READREG_VALID
#define V_UOP_READY V_READREG_READY
#define UOP_FIELDS READREG_FIELDS

    REPEAT_4(BIND_FIELDS)
    REPEAT_4(BIND_VALID)
    REPEAT_4(BIND_READY)

    // * writebackUop
#define UOP writebackUop
#define V_UOP V_WRITEBACK_UOP
#define V_UOP_VALID V_WRITEBACK_VALID
#define UOP_FIELDS WRITEBACK_FIELDS

    REPEAT_4(BIND_FIELDS)
    REPEAT_4(BIND_VALID)

    // * commitUop
#define UOP commitUop
#define V_UOP V_COMMIT_UOP
#define V_UOP_VALID V_COMMIT_VALID
#define UOP_FIELDS COMMIT_FIELDS

    REPEAT_1(BIND_FIELDS)
    REPEAT_1(BIND_VALID)

    // * flagUop
#define UOP flagUop
#define V_UOP V_FLAG_UOP
#define V_UOP_VALID V_FLAG_VALID
#define UOP_FIELDS FLAG_FIELDS

    REPEAT_1(BIND_FIELDS)
  }

  void log(uint64_t cycle) {
    if (begin_wave) {
      printf("cycle: %ld\n", cycle);
    }
    if (cycle > lastCommit + 100) {
      Log("CPU hangs");
      running = false;
    }
    // * flag

    // * commit
    char buf[512];
    for (int i = 0; i < 1; ++i) {
      if (*commitUop[i].valid && *commitUop[i].ready) {
        ++instRetired;
        lastCommit = cycle;
        auto robIndex = *commitUop[i].robPtr_index;
        auto &inst = insts[robIndex];
        auto &uop = commitUop[i];
        // inst.valid = false;
        if (inst.fuType == FuType::LSU) {
          auto addr = inst.src1 + inst.imm;
          if (addr >= 0x11000000 + 0xbff8 && addr < 0x11000000 + 0xc000 ||
              addr >= 0x11000000 + 0x4000 && addr < 0x11000000 + 0x4008) {
            access_device = true;
          }
        }
        if (begin_wave) {
          itrace_generate(buf, inst.pc, inst.inst);
          printf("[%3d] %s\n", robIndex, buf);
          printf("      rd  = %2d  rs1  = %2d  rs2  = %2d\n", inst.rd, inst.rs1,
                 inst.rs2);
          printf("      prd = %2d  prs1 = %2d  prs2 = %2d\n", inst.prd,
                 inst.prs1, inst.prs2);
          printf("      src1   = %d/%u/0x%x\n", inst.src1, inst.src1,
                 inst.src1);
          printf("      src2   = %d/%u/0x%x\n", inst.src2, inst.src2,
                 inst.src2);
          printf("      result = %d/%u/0x%x\n", inst.result, inst.result,
                 inst.result);
        }
        if (*uop.rd) {
          if (begin_wave) {
            printf("      archTable[%d] = %d\n", *uop.rd, *uop.prd);
          }
          archTable[*uop.rd] = *uop.prd;
        }
        pc = inst.pc + 4;
#ifdef DIFFTEST
        difftest();
#endif
      }
    }

    // * writeback
    for (int i = 0; i < 4; ++i) {
      if (*writebackUop[i].valid && *writebackUop[i].ready) {
        auto robIndex = *writebackUop[i].robPtr_index;
        auto &inst = insts[robIndex];
        auto &uop = writebackUop[i];
        if (*uop.prd) {
          if (begin_wave) {
            printf("writeback: pReg[%d] = %d\n", *uop.prd,
                   *writebackUop[i].data);
          }
          pReg[*uop.prd] = *writebackUop[i].data;
        }
        inst.result = *writebackUop[i].data;
        inst.executed = true;
        inst.flag = (Flags)*writebackUop[i].flag;
      }
    }

    // * read register
    for (int i = 0; i < 4; ++i) {
      if (*readRegUop[i].valid && *readRegUop[i].ready) {
        auto robIndex = *readRegUop[i].robPtr_index;
        auto &inst = insts[robIndex];
        inst.src1 = *readRegUop[i].src1;
        inst.src2 = *readRegUop[i].src2;
      }
    }

    // * rename
    for (int i = 0; i < 1; ++i) {
      if (*renameUop[i].valid && *renameUop[i].ready) {
        auto &inst = insts[*renameUop[i].robPtr_index];
        auto &uop = renameUop[i];
        inst.inst = *uop.inst;
        inst.fuType = *uop.fuType;
        inst.opcode = *uop.opcode;
        inst.imm = *uop.imm;
        inst.pc = *uop.pc;

        inst.rd = *uop.rd;
        inst.rs1 = *uop.rs1;
        inst.rs2 = *uop.rs2;
        inst.prd = *uop.prd;
        inst.prs1 = *uop.prs1;
        inst.prs2 = *uop.prs2;
        inst.valid = true;
      }
    }
  }

  void printInsts() {
    printf("============================================\n");
    char buf[512];
    for (int i = 0; i < 128; ++i) {
      if (insts[i].valid) {
        auto &inst = insts[i];
        itrace_generate(buf, inst.pc, inst.inst);
        printf("[%3d] %s\n", i, buf);
        printf("      rd  = %2d  rs1  = %2d  rs2  = %2d\n", inst.rd, inst.rs1,
               inst.rs2);
        printf("      prd = %2d  prs1 = %2d  prs2 = %2d\n", inst.prd, inst.prs1,
               inst.prs2);
        printf("      src1   = %d/%u/0x%x\n", inst.src1, inst.src1, inst.src1);
        printf("      src2   = %d/%u/0x%x\n", inst.src2, inst.src2, inst.src2);
        printf("      result = %d/%u/0x%x\n", inst.result, inst.result,
               inst.result);
      }
    }
  }

  uint32_t getPC() { return pc; }

  uint32_t getReg(int index) { return pReg[archTable[index]]; }

  uint64_t getInstRetired() { return instRetired; }
};

#endif