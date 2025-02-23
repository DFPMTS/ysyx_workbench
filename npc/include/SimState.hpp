#ifndef SIMSTATE_HPP
#define SIMSTATE_HPP

#include "CSR.hpp"
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
  CSRCtrl csrCtrl[1];
  CSR csr;

  InstInfo insts[128];
  uint32_t archTable[32] = {};
  uint32_t pReg[64] = {};
  uint32_t pc = 0;

  uint64_t lastCommit;
  uint64_t instRetired = 0;

  InstInfo commited[32];
  uint32_t commitedIndex = 0;

  uint32_t difftestCountdown = 0;
  bool waitDifftest = false;

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
    REPEAT_1(BIND_VALID)

#define UOP csrCtrl
#define V_UOP V_CSR_CTRL
#define UOP_FIELDS CSR_CTRL_FIELDS

    REPEAT_1(BIND_FIELDS)

#define CSRS csr
    CSR_FIELDS(BIND_CSRS_FIELD);
    printf("stvec: %p\n", csr.stvec);
    printf("bind uops done\n");
  }

  void log(uint64_t cycle) {
    --difftestCountdown;
    // if (waitDifftest) {
    //   printf("wait difftest: %d\n", difftestCountdown);
    // }
    if (begin_wave) {
      printf("cycle: %ld\n", cycle);
    }
    if (cycle > lastCommit + 200) {
      Log("CPU hangs");
      stop = Stop::CPU_HANG;
      running.store(false);
    }
    char buf[512];
    // * flag
    for (int i = 0; i < 1; ++i) {
      if (*flagUop[i].valid && *flagUop[i].ready) {
        auto robIndex = *flagUop[i].robPtr_index;
        auto &inst = insts[robIndex];
        auto flag = (FlagOp)*flagUop[i].flag;
        auto decodeFlag = (DecodeFlagOp)*flagUop[i].rd;

        // * skip the difftest since inst has commited but CSR is not changed
        // * for now / redirect signal is not fired
        if (flag == FlagOp::INTERRUPT) {
          // * override the difftest ref
          access_device = true;
        }

        if (flag != FlagOp::MISPREDICT) {
          if (flag == FlagOp::DECODE_FLAG) {
            if (decodeFlag == DecodeFlagOp::FENCE ||
                decodeFlag == DecodeFlagOp::FENCE_I ||
                decodeFlag == DecodeFlagOp::SFENCE_VMA) {
              continue;
            }
          }
          // itrace_generate(buf, inst.pc, inst.inst);
          // fprintf(stderr, "\033[32m");
          // fprintf(stderr, "<%3d> %s\n", robIndex, buf);
          // fprintf(stderr, "      pc   = %x\n", *flagUop[i].pc);
          // fprintf(stderr, "      flag = %s\n", getFlagOpName(flag));
          // if (flag == FlagOp::DECODE_FLAG) {
          //   fprintf(stderr, "      decodeFlag = %s\n",
          //           getDecodeFlagOpName(decodeFlag));
          // }
          // fprintf(stderr, "\033[0m");
        }
      }
    }
    // * fix PC with redirect
    if (V_REDIRECT_VALID) {
      if (begin_wave) {
        printf("PC redirect to %x\n", V_REDIRECT_PC);
      }
      pc = V_REDIRECT_PC;
    }

    // * CSR Ctrl
    {
      auto pc = *csrCtrl[0].pc;
      auto trap = *csrCtrl[0].trap;
      auto intr = *csrCtrl[0].intr;
      auto mret = *csrCtrl[0].mret;
      auto sret = *csrCtrl[0].sret;
      auto deleg = *csrCtrl[0].delegate;
      // if (trap || mret || sret) {
      //   fprintf(stderr, "\033[33m");
      //   if (trap) {
      //     fprintf(stderr, "%s on PC: %x:\n", (intr ? "Interrupt" :
      //     "Exception"),
      //             pc);
      //     fprintf(stderr, "      cause = %d\n", *csrCtrl[0].cause);
      //     fprintf(stderr, "      delegate = %s\n", (deleg ? "yes" : "no"));
      //   }
      //   if (mret) {
      //     fprintf(stderr, "mret on PC: %x\n", pc);
      //   }
      //   if (sret) {
      //     fprintf(stderr, "sret on PC: %x\n", pc);
      //   }
      //   fprintf(stderr, "\033[0m");
      // }
    }

    if (waitDifftest && difftestCountdown == 0) {
      difftest();
      waitDifftest = false;
    }

    // * commit
    for (int i = 0; i < 1; ++i) {
      if (*commitUop[i].valid && *commitUop[i].ready) {
        ++instRetired;
        lastCommit = cycle;
        auto robIndex = *commitUop[i].robPtr_index;
        auto &inst = insts[robIndex];
        auto &uop = commitUop[i];

        waitDifftest = inst.flag != FlagOp::NONE;

        if (inst.fuType == FuType::LSU) {
          auto addr = inst.src1 + inst.imm;
          if (addr >= 0x11000000 + 0xbff8 && addr < 0x11000000 + 0xc000 ||
              addr >= 0x11000000 + 0x4000 && addr < 0x11000000 + 0x4008 ||
              addr >= 0x11000000 + 0x0000 && addr < 0x11000000 + 0x0004) {
            access_device = true;
          }
        }
        if (inst.fuType == FuType::CSR) {
          auto csrAddr = inst.imm & ((1 << 12) - 1);
          if (csrAddr == 0xC01 || csrAddr == 0xC81) {
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
          printf("      flag = %s\n", getFlagOpName(inst.flag));
          printf("      target = %x\n", inst.target);
        }
        if (*uop.rd) {
          if (begin_wave) {
            printf("      archTable[%d] = %d\n", *uop.rd, *uop.prd);
          }
          archTable[*uop.rd] = *uop.prd;
        }
        // * trace commited inst
        commited[commitedIndex++] = inst;
        if (commitedIndex >= 32) {
          commitedIndex = 0;
        }
        pc = inst.pc + 4;
#ifdef DIFFTEST
        if (!waitDifftest) {
          difftest();
        } else {
          // printf("Wait difftest!\n");
          difftestCountdown = 2;
        }
#endif
      }
    }

    // * writeback
    for (int i = 0; i < 4; ++i) {
      if (*writebackUop[i].valid && *writebackUop[i].ready) {
        auto robIndex = *writebackUop[i].robPtr_index;
        auto &inst = insts[robIndex];
        auto &uop = writebackUop[i];
        // * the writeback may go to ROB or PTW
        if ((Dest)*uop.dest == Dest::ROB) {
          if (*uop.prd) {
            if (begin_wave) {
              printf("writeback: pReg[%d] = %d\n", *uop.prd,
                     *writebackUop[i].data);
            }
            pReg[*uop.prd] = *writebackUop[i].data;
          }
          inst.result = *writebackUop[i].data;
          inst.executed = true;
          inst.flag = (FlagOp)*writebackUop[i].flag;
          inst.target = *writebackUop[i].target;
        }
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
        if (inst.fuType == FuType::FLAG) {
          inst.flag = (FlagOp)*uop.opcode;
        }

        inst.rd = *uop.rd;
        inst.rs1 = *uop.rs1;
        inst.rs2 = *uop.rs2;
        inst.prd = *uop.prd;
        inst.prs1 = *uop.prs1;
        inst.prs2 = *uop.prs2;
        inst.ldqPtr_index = *uop.ldqPtr_index;
        inst.ldqPtr_flag = *uop.ldqPtr_flag;
        inst.stqPtr_index = *uop.stqPtr_index;
        inst.stqPtr_flag = *uop.stqPtr_flag;
        inst.valid = true;
      }
    }
  }

  void printInst(InstInfo *inst, int id) {
    char buf[512];
    itrace_generate(buf, inst->pc, inst->inst);
    printf("[%3d] %s\n", id, buf);
    printf("      rd  = %2d  rs1  = %2d  rs2  = %2d\n", inst->rd, inst->rs1,
           inst->rs2);
    printf("      prd = %2d  prs1 = %2d  prs2 = %2d\n", inst->prd, inst->prs1,
           inst->prs2);
    printf("      ldqIndex = %d  stqIndex = %d\n", inst->ldqPtr_index,
           inst->stqPtr_index);
    printf("      src1   = %d/%u/0x%x\n", inst->src1, inst->src1, inst->src1);
    printf("      src2   = %d/%u/0x%x\n", inst->src2, inst->src2, inst->src2);
    printf("      result = %d/%u/0x%x\n", inst->result, inst->result,
           inst->result);
    printf("      flag = %s\n", getFlagOpName(inst->flag));
    printf("      target = %x\n", inst->target);
  }

  void printInsts() {
    printf("=======================ROB=====================\n");
    char buf[512];
    for (int i = 0; i < 128; ++i) {
      if (insts[i].valid) {
        printInst(&commited[i], i);
      }
    }
  }

  void printCommited() {
    printf("======================Commited======================\n");
    char buf[512];
    for (int i = 0; i < 32; ++i) {
      int index = commitedIndex + i;
      if (index >= 32) {
        index -= 32;
      }
      if (commited[index].valid) {
        printInst(&commited[index], i);
      }
    }
  }

  uint32_t getPC() { return pc; }

  uint32_t getReg(int index) { return pReg[archTable[index]]; }

  uint64_t getInstRetired() { return instRetired; }
};

extern SimState state;

#endif