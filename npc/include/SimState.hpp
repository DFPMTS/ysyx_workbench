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
  DecodeUop decodeUop[ISSUE_WIDTH];
  RenameUop renameROBUop[ISSUE_WIDTH];
  Uop renameIQUop[ISSUE_WIDTH];
  RenameUop issueUop[MACHINE_WIDTH];
  WritebackUop writebackUop[WRITEBACK_WIDTH];
  ReadRegUop readRegUop[MACHINE_WIDTH];
  CommitUop commitUop[COMMIT_WIDTH];
  FlagUop flagUop[1];
  CSRCtrl csrCtrl[1];
  AGUUop aguUop[1];
  CSR csr;

  uint64_t konataInstId = 0;
  uint64_t decodeInstIds[ISSUE_WIDTH];
  bool decodeInstValid[ISSUE_WIDTH];

  Ptr robHeadPtr = Ptr(ROB_SIZE, 0, 0);
  Ptr robTailPtr = Ptr(ROB_SIZE, 1, 0);

  InstInfo insts[ROB_SIZE];
  uint32_t archTable[32] = {};
  uint32_t pReg[NUM_PREG] = {};
  uint32_t pc = 0;

  uint64_t lastCommit;
  uint64_t instRetired = 0;

  InstInfo commited[32];
  uint32_t commitedIndex = 0;

  uint32_t difftestCountdown = 0;
  bool waitDifftest = false;

  uint64_t totalBranches = 0;
  uint64_t totalBranchMispred = 0;

  uint64_t totalJumps = 0;
  uint64_t totalJumpMispred = 0;

  uint64_t mispredPenalty = 0;

  FILE *konataFile = nullptr;

  uint64_t debugStopCycle = -1;

  FILE *customFile = nullptr;

  // #define KONATA

  void konataLogStage(uint64_t instId, const char *stage) {
#ifdef KONATA
    fprintf(konataFile, "S\t%lu\t0\t%s\n", instId, stage);
#endif
  }

  void konataLogDecode(uint64_t instId, word_t pc, uint32_t inst) {
#ifdef KONATA
    char buf[512];
    itrace_generate(buf, pc, inst);
    auto buf_len = strlen(buf);
    for (int i = 0; i < buf_len; ++i) {
      if (buf[i] == '\t') {
        buf[i] = ' ';
      }
    }
    fprintf(konataFile, "I\t%lu\t0\t0\n", instId);
    fprintf(konataFile, "L\t%lu\t0\t%s\n", instId, buf);
    konataLogStage(instId, "RN");
#endif
  }

  void konataLogRename(InstInfo *inst) {
    if (inst->fuType == FuType::FLAG) {
      konataLogStage(inst->konataId, "CM");
    } else {
      konataLogStage(inst->konataId, "IS");
    }
  }

  void konataLogIssue(InstInfo *inst) { konataLogStage(inst->konataId, "RF"); }

  void konataLogReadReg(InstInfo *inst) {
    konataLogStage(inst->konataId, "EX");
  }

  void konataLogWriteback(InstInfo *inst) {
    konataLogStage(inst->konataId, "CM");
  }

  void konataLogCommit(InstInfo *inst) {
#ifdef KONATA
    fprintf(konataFile, "R\t%lu\t%d\t0\n", inst->konataId, inst->robPtr_index);
#endif
  }

  void konataLogFlush(InstInfo *inst) {
#ifdef KONATA
    fprintf(konataFile, "R\t%lu\t%d\t1\n", inst->konataId, inst->robPtr_index);
#endif
  }

  void konataLogFlush(uint64_t instId) {
#ifdef KONATA
    fprintf(konataFile, "R\t%lu\t0\t1\n", instId);
#endif
  }

  void konataLogCycle(uint64_t cycle) {
#ifdef KONATA
    fprintf(konataFile, "C\t1\t//%lu\n", cycle);
#endif
  }

  void bindUops() {
    // * decodeUop
#define UOP decodeUop
#define V_UOP V_DECODE_UOP
#define V_UOP_VALID V_DECODE_VALID
#define V_UOP_READY V_DECODE_READY
#define UOP_FIELDS DECODE_FIELDS
    REPEAT_3(BIND_FIELDS)
    REPEAT_3(BIND_VALID)
    REPEAT_3(BIND_READY)

    // * renameUop -> ROB
#define UOP renameROBUop
#define V_UOP V_RENAME_UOP
#define V_UOP_VALID V_RENAME_ROB_VALID
#define UOP_FIELDS RENAME_FIELDS
    REPEAT_3(BIND_FIELDS)
    REPEAT_3(BIND_VALID)

    // * renameUop -> IQ
#define UOP renameIQUop
// #define V_UOP V_RENAME_UOP
#define V_UOP_VALID V_RENAME_IQ_VALID
#define V_UOP_READY V_RENAME_IQ_READY
    REPEAT_3(BIND_VALID)
    REPEAT_3(BIND_READY)

// * IQ -> ReadReg
#define UOP issueUop
#define V_UOP V_ISSUE_UOP
#define V_UOP_VALID V_ISSUE_VALID
#define V_UOP_READY V_ISSUE_READY
#define UOP_FIELDS RENAME_FIELDS
    REPEAT_4(BIND_FIELDS)
    REPEAT_4(BIND_VALID)
    REPEAT_4(BIND_READY)

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

    REPEAT_5(BIND_FIELDS)
    REPEAT_5(BIND_VALID)

    // * commitUop
#define UOP commitUop
#define V_UOP V_COMMIT_UOP
#define V_UOP_VALID V_COMMIT_VALID
#define UOP_FIELDS COMMIT_FIELDS

    REPEAT_3(BIND_FIELDS)
    REPEAT_3(BIND_VALID)

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

#define UOP aguUop
#define V_UOP V_AGU_UOP
#define V_UOP_VALID V_AGU_VALID
#define UOP_FIELDS AGU_FIELDS

    REPEAT_1(BIND_FIELDS)
    REPEAT_1(BIND_VALID)

#define CSRS csr
    CSR_FIELDS(BIND_CSRS_FIELD);
    printf("bind uops done\n");

    konataFile = fopen("konata.log", "w");
    fprintf(konataFile, "Kanata 0004\n");
    fprintf(konataFile, "C=0\n");

    customFile = fopen("custom.log", "w");
  }

  void log(uint64_t cycle) {

    if (cycle % 100 == 0) {
      fprintf(customFile, "%lu %lu\n", cycle, instRetired);
    }

    if (cycle > debugStopCycle) {
      running.store(false);
      stop = Stop::DIFFTEST_FAILED;
    }
    --difftestCountdown;
    // if (waitDifftest) {
    //   printf("wait difftest: %d\n", difftestCountdown);
    // }
    if (begin_wave || begin_log) {
      printf("cycle: %ld\n", cycle);
      fflush(stdout);
    }
    konataLogCycle(cycle);
    if (cycle > lastCommit + 20000) {
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
        // if (flag == FlagOp::DECODE_FLAG &&
        //     decodeFlag == DecodeFlagOp::INTERRUPT) {
        //   // * override the difftest ref
        //   if (begin_wave || begin_log) {
        //     fprintf(stderr, "INTERRUPT on PC: %x:\n", *flagUop[i].pc);
        //   }
        //   access_device = true;
        // }

        if (flag == FlagOp::DECODE_FLAG && decodeFlag == DecodeFlagOp::EBREAK) {
          running.store(false);
        }

        // if (flag != FlagOp::MISPREDICT) {
        //   if (flag == FlagOp::DECODE_FLAG) {
        //     if (decodeFlag == DecodeFlagOp::FENCE ||
        //         decodeFlag == DecodeFlagOp::FENCE_I ||
        //         decodeFlag == DecodeFlagOp::SFENCE_VMA) {
        //       continue;
        //     }
        //   }
        //   // itrace_generate(buf, inst.pc, inst.inst);
        //   // fprintf(stderr, "\033[32m");
        //   // fprintf(stderr, "<%3d> %s\n", robIndex, buf);
        //   // fprintf(stderr, "      pc   = %x\n", *flagUop[i].pc);
        //   // fprintf(stderr, "      flag = %s\n", getFlagOpName(flag));
        //   // if (flag == FlagOp::DECODE_FLAG) {
        //   //   fprintf(stderr, "      decodeFlag = %s\n",
        //   //           getDecodeFlagOpName(decodeFlag));
        //   // }
        //   // fprintf(stderr, "\033[0m");
        // }
      }
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
      if (begin_wave || begin_log) {
        fprintf(stderr, "Delayed Difftest, access_device = %d\n",
                access_device);
      }
      difftest();
      waitDifftest = false;
    }

    // * commit
    for (int i = 0; i < COMMIT_WIDTH; ++i) {
      if (*commitUop[i].valid && *commitUop[i].ready) {
        ++instRetired;
        robTailPtr.inc();
        lastCommit = cycle;
        auto robIndex = *commitUop[i].robPtr_index;
        auto &inst = insts[robIndex];
        auto &uop = commitUop[i];

        if (inst.fuType == FuType::BRU) {
          if ((uint32_t)inst.opcode <= (uint32_t)BRUOp::JAL) {
            totalJumps++;
            if (inst.flag == FlagOp::MISPREDICT_JUMP) {
              totalJumpMispred++;
              mispredPenalty += cycle - inst.resultValidCycle;
            }
          } else {
            totalBranches++;
            if (inst.flag == FlagOp::MISPREDICT_TAKEN ||
                inst.flag == FlagOp::MISPREDICT_NOT_TAKEN) {
              totalBranchMispred++;
            }
          }
        }
        waitDifftest = (inst.flag != FlagOp::NONE) &&
                       (inst.flag != FlagOp::BRANCH_TAKEN) &&
                       (inst.flag != FlagOp::BRANCH_NOT_TAKEN);
        if (inst.fuType == FuType::LSU) {
          auto addr = inst.paddr;
          if (addr >= CLINT_BASE + 0xbff8 && addr < CLINT_BASE + 0xc000 ||
              addr >= CLINT_BASE + 0x4000 && addr < CLINT_BASE + 0x4008 ||
              addr >= CLINT_BASE + 0x0000 && addr < CLINT_BASE + 0x0004 ||
              addr >= UART_BASE && addr < UART_BASE + 32) {
            // fprintf(stderr, "LSU MMIO: %x\n", addr);
            access_device = true;
          }
        }
        if (inst.fuType == FuType::CSR) {
          // TODO: skip mip register
          auto csrAddr = inst.imm & ((1 << 12) - 1);
          if (csrAddr == 0xC01 || csrAddr == 0xC81) {
            // fprintf(stderr, "CSR MMIO: %x\n", csrAddr);
            access_device = true;
          }
        }
        if (inst.flag == FlagOp::DECODE_FLAG &&
            (DecodeFlagOp)inst.rd == DecodeFlagOp::INTERRUPT) {
          // * override the difftest ref
          if (begin_wave || begin_log) {
            fprintf(stderr, "INTERRUPT on PC: %x:\n", *flagUop[i].pc);
          }
          access_device = true;
        }
        if (begin_wave || begin_log) {
          printf("commit[%d]: ", robIndex);
          printf("access_device = %s\n", access_device ? "True" : "False");
          printf("waitDifftest = %s\n", waitDifftest ? "True" : "False");
          printf("difftestCountdown = %d\n", difftestCountdown);
          printInst(&inst, robIndex);
        }
        // PTW AMO bug
        // if (inst.pc == 0xc01527c8 && inst.paddr == 0x1fd75288) {
        //   printf("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\n");
        //   printf("pc = %x\n", inst.pc);
        //   printf("instRetired = %lu\n", instRetired);
        //   printf("cycle = %lu\n", cycle);
        //   debugStopCycle = cycle + FORK_CYCLE / 2;
        //   printf("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\n");
        // }
        if (*uop.rd) {
          if (begin_wave || begin_log) {
            printf("      archTable[%d] = %d\n", *uop.rd, *uop.prd);
          }
          archTable[*uop.rd] = *uop.prd;
        }
        // * trace commited inst
        commited[commitedIndex++] = inst;
        if (commitedIndex >= 32) {
          commitedIndex = 0;
        }

        konataLogCommit(&inst);

#ifdef DIFFTEST
        if (!waitDifftest) {
          if (inst.fuType == FuType::BRU) {
            pc = inst.predTarget;
          } else {
            pc = inst.pc + 4;
          }
          if (begin_wave || begin_log) {
            fprintf(stderr, "[%d] Do difftest\n", robIndex);
          }
          difftest();
        } else {
          difftestCountdown = 2;
        }
#endif
      }
    }

    // * writeback
    for (int i = 0; i < WRITEBACK_WIDTH; ++i) {
      if (*writebackUop[i].valid && *writebackUop[i].ready) {
        auto robIndex = *writebackUop[i].robPtr_index;
        auto &inst = insts[robIndex];
        auto &uop = writebackUop[i];
        // * the writeback may go to ROB or PTW
        if ((Dest)*uop.dest == Dest::ROB) {
          if (*uop.prd) {
            if (begin_wave || begin_log) {
              printf("writeback[%d]: pReg[%d] = %d\n", i, *uop.prd,
                     *writebackUop[i].data);
            }
            pReg[*uop.prd] = *writebackUop[i].data;
          }
          inst.result = *writebackUop[i].data;
          inst.resultValidCycle = cycle;
          inst.executed = true;
          inst.flag = (FlagOp)*writebackUop[i].flag;
          inst.target = *writebackUop[i].target;

          // printf("------------writeback[%d]: kanataId=%lu\n", robIndex,
          //        inst.konataId);
          konataLogWriteback(&inst);
        }
      }
    }

    // * agu
    for (int i = 0; i < 1; ++i) {
      if (*aguUop[i].valid && *aguUop[i].ready) {
        auto robIndex = *aguUop[i].robPtr_index;
        auto &inst = insts[robIndex];
        auto &uop = aguUop[i];
        inst.paddr = *aguUop[i].addr;
        if (begin_wave || begin_log) {
          printf("AGU: [%3d] paddr = %x\n", robIndex, inst.paddr);
        }
      }
    }

    // * read register
    for (int i = 0; i < MACHINE_WIDTH; ++i) {
      if (*readRegUop[i].valid && *readRegUop[i].ready) {
        auto robIndex = *readRegUop[i].robPtr_index;
        auto &inst = insts[robIndex];
        inst.src1 = *readRegUop[i].src1;
        inst.src2 = *readRegUop[i].src2;

        konataLogReadReg(&inst);
      }
    }

    // * issue
    for (int i = 0; i < MACHINE_WIDTH; ++i) {
      if (*issueUop[i].valid && *issueUop[i].ready) {
        auto robIndex = *issueUop[i].robPtr_index;
        auto &inst = insts[robIndex];
        konataLogIssue(&inst);
      }
    }
    // * fix PC with redirect
    if (V_REDIRECT_VALID) {
      if (begin_wave || begin_log) {
        printf("PC redirect to %x\n", V_REDIRECT_PC);
      }
      for (int i = 0; i < ROB_SIZE; ++i) {
        if ((robHeadPtr.m_flag == robTailPtr.m_flag) &&
                (i >= robTailPtr.m_index || i < robHeadPtr.m_index) ||
            (robHeadPtr.m_flag != robTailPtr.m_flag) &&
                (i >= robTailPtr.m_index && i < robHeadPtr.m_index)) {
          insts[i].valid = false;
          konataLogFlush(&insts[i]);
        }
      }
      robHeadPtr.reset(0);
      robTailPtr.reset(1);
      for (int i = 0; i < ISSUE_WIDTH; ++i) {
        if (decodeInstValid[i]) {
          konataLogFlush(decodeInstIds[i]);
        }
      }
      pc = V_REDIRECT_PC;
      for (int i = 0; i < ROB_SIZE; ++i) {
        insts[i].valid = false;
      }
    } else {
      // * rename -> ROB
      for (int i = 0; i < ISSUE_WIDTH; ++i) {
        if (*renameROBUop[i].valid && *renameROBUop[i].ready) {
          auto &inst = insts[*renameROBUop[i].robPtr_index];
          auto &uop = renameROBUop[i];
          robHeadPtr.inc();
          inst.konataId = decodeInstIds[i];
          inst.robPtr_index = *uop.robPtr_index;
          // printf("------------rob[%d]: kanataId=%lu\n", inst.robPtr_index,
          //        inst.konataId);
          inst.inst = *uop.inst;
          inst.fuType = *uop.fuType;
          inst.opcode = *uop.opcode;
          inst.imm = *uop.imm;
          inst.pc = *uop.pc;
          inst.predTarget = *uop.predTarget;
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
      // * rename -> IQ
      for (int i = 0; i < ISSUE_WIDTH; ++i) {
        if (renameIQUop[i].isFire()) {
          auto &inst = insts[*renameROBUop[i].robPtr_index];
          auto &uop = renameROBUop[i];
          konataLogRename(&inst);
        }
      }
      // * decode
      for (int i = 0; i < ISSUE_WIDTH; ++i) {
        if (decodeUop[i].isFire()) {
          auto &uop = decodeUop[i];
          decodeInstIds[i] = konataInstId++;
          decodeInstValid[i] = true;
          konataLogDecode(decodeInstIds[i], *uop.pc, *uop.inst);
        } else {
          decodeInstValid[i] = false;
        }
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
    if (inst->fuType == FuType::LSU || inst->fuType == FuType::AMO) {
      printf("      paddr = 0x%x\n", inst->paddr);
    }
    // * Executed
    printf("      executed = %s\n", inst->executed ? "True" : "False");
    printf("      result = %d/%u/0x%x\n", inst->result, inst->result,
           inst->result);
    printf("      flag = %s\n", getFlagOpName(inst->flag));
    printf("      target = %x\n", inst->target);
    fflush(stdout);
  }

  void printInsts() {
    printf("=======================ROB=====================\n");
    char buf[512];
    for (int i = 0; i < ROB_SIZE; ++i) {
      if (insts[i].valid) {
        printInst(&insts[i], i);
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

  void printArchRenameTable() {
    printf("======================Arch Rename Table======================\n");
    for (int i = 0; i < 32; ++i) {
      printf("archTable[%d] = %d\n", i, archTable[i]);
    }
  }

  void printPerfStat() {
    // * Branch / Jump Mispred rate
    printf("======================Perf Stat======================\n");
    printf("Total Inst Retired: %lu\n", instRetired);
    printf("Total Cycles: %lu\n", lastCommit);
    printf("IPC: %.2f\n", (double)instRetired / lastCommit);
    printf("-----------------------Branch------------------------\n");
    printf("Total Branch: %lu\n", totalBranches);
    printf("Total Branch Mispred: %lu\n", totalBranchMispred);
    printf("Branch Mispred Rate: %.2f%%\n",
           (double)totalBranchMispred / totalBranches * 100);
    printf("-----------------------Jump--------------------------\n");
    printf("Total Jump: %lu\n", totalJumps);
    printf("Total Jump Mispred: %lu\n", totalJumpMispred);
    printf("Jump Mispred Rate: %.2f%%\n",
           (double)totalJumpMispred / totalJumps * 100);
    printf("Mispred Penalty: %lu\n", mispredPenalty);
    printf("Mispred Penalty Rate: %.2f%%\n",
           (double)mispredPenalty / lastCommit * 100);
  }

  uint32_t getPC() { return pc; }

  uint32_t getReg(int index) { return pReg[archTable[index]]; }

  uint64_t getInstRetired() { return instRetired; }
};

extern SimState state;

#endif