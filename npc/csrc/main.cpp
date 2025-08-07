#include "EventMonitor.hpp"
#include "SimState.hpp"
#include "Uop.hpp"
#include "cpu.hpp"
#include "debug.hpp"
#include "difftest.hpp"
#include "func_sym.hpp"
#include "lightsss.hpp"
#include "mem.hpp"
#include "monitor.hpp"
#include "status.hpp"
#include <chrono>
#include <csignal>
#include <cstdint>
#include <cstdlib>

void nvboard_update();
void nvboard_quit();
void nvboard_init(int x);
uint64_t totalCycles = 0;

void printPerfCounters() {
  std::cout << "-----------------------------------" << std::endl;
  std::cout << "ifuFinished" << " " << getEventCount("ifuFinished")
            << std::endl;
  std::cout << "icacheMiss" << " " << getEventCount("icacheMiss") << std::endl;
  std::cout << "ifuStalled" << " " << getEventCount("ifuStalled") << std::endl;

  std::cout << "iduBruInst" << " " << getEventCount("iduBruInst") << std::endl;
  std::cout << "iduAluInst" << " " << getEventCount("iduAluInst") << std::endl;
  std::cout << "iduMemInst" << " " << getEventCount("iduMemInst") << std::endl;
  std::cout << "iduCsrInst" << " " << getEventCount("iduCsrInst") << std::endl;

  std::cout << "memFinished" << " " << getEventCount("memFinished")
            << std::endl;
  std::cout << "memStalled" << " " << getEventCount("memStalled") << std::endl;

  std::cout << "totalBranch" << " " << getEventCount("totalBranch")
            << std::endl;

  std::cout << "branchMisPred" << " " << getEventCount("branchMisPred")
            << std::endl;

  std::cout << "Total cycles: " << totalCycles << std::endl;

  std::cout << "-----------------------------------" << std::endl;
}
class SimulationSpeed {
private:
  // Record the start time
  std::chrono::time_point<std::chrono::system_clock> start;

public:
  void initTimer() { start = std::chrono::system_clock::now(); }
  void printSimulationSpeed(uint64_t cycles) {
    auto end = std::chrono::system_clock::now();
    std::chrono::duration<double> elapsed_seconds = end - start;
    std::cout << "Simulation Speed:  " << cycles / elapsed_seconds.count()
              << " cycles/s" << std::endl;
  }
};

extern "C" {
void mem_read(uint32_t addr, svBitVecVal *result);
}

int main(int argc, char *argv[]) {
  Verilated::commandArgs(argc, argv);
  srand(time(0));
  fprintf(stderr, "MAIN begin : pid=%d\n", getpid());
  // Verilated::commandArgs(argc, argv);
  gpr = [&](int index) { return state.getReg(index); };
  PC = [&]() { return state.getPC(); };
  init_monitor(argc, argv);
  LightSSS lightsss;
  SimulationSpeed sim_speed;
  bool commit = false;
  bool booted = false;
#ifdef NVBOARD
  nvboard_init(1);
#endif
  Log("Simulation begin");
  sim_speed.initTimer();
  int T = 400;
  // begin_wave = true;
  // begin_log = true;
  bool forked = false;
  signal(SIGINT, [](int) {
    fprintf(stderr, "Vtop: SIGINT received");
    stop = Stop::INTERRUPT;
    running.store(false);
  });
  uint32_t temp[8];
  uint64_t lastForkCycle = 0;
  // T = 10000;
  while (running.load()) {
    // if (state.getInstRetired() > 860000) {
    //   stop = Stop::DIFFTEST_FAILED;
    //   running.store(false);
    //   // begin_wave = true;
    // }
    // if (totalCycles > 200000 && totalCycles < 220000) {
    //   begin_wave = true;
    // } else {
    //   begin_wave = false;
    // }
    if (!lightsss.is_child()) {
      if (totalCycles > lastForkCycle + FORK_CYCLE || !forked) {
        forked = true;
        auto ret = lightsss.do_fork();
        switch (ret) {
        case FORK_CHILD:
          top->atClone();
          begin_wave = true;
          // begin_log = true;
          break;

        default:
          break;
        }
        // if (lightsss.is_child()) {
        //   fprintf(stderr, "[CHILD] pid(%d) forked on cycle %ld\n", getpid(),
        //           totalCycles);

        // } else {
        //   fprintf(stderr, "[PARENT] pid(%d) forked on cycle %ld\n", getpid(),
        //           totalCycles);
        // }
        lastForkCycle = totalCycles;
      }
    }

    cpu_step();
    state.log(totalCycles);

    ++totalCycles;
  }

  if (!lightsss.is_child()) {
    fflush(stdout);
    fprintf(stderr, "[PARENT] pid(%d) endCycles %lu\n", getpid(),
            lightsss.get_end_cycles());
    if (stop != Stop::EBREAK && stop != Stop::INTERRUPT) {
      lightsss.wakeup_child(totalCycles);
      fprintf(stderr, "[PARENT] wakeup_child end\n");
    }
    lightsss.do_clear();
    if (stop != Stop::EBREAK && stop != Stop::INTERRUPT) {
      return -1;
    }
  } else {
    fprintf(stderr, "[CHILD] pid(%d) endCycles %lu execution end\n", getpid(),
            lightsss.get_end_cycles());
  }

  std::cerr << "Simulation End" << std::endl;
  printf("Total cycles:\t %lu\n", totalCycles);
  printf("Total instructions:\t %lu\n", state.getInstRetired());
  state.printCommited();
  state.printInsts();
  state.printArchRenameTable();
  get_context(&dut);
  isa_reg_display(&dut);
  state.printPerfStat();
  ++totalCycles;
  // a0
  int retval = state.getReg(10);

#ifdef WAVE
  fst->close();
#endif

  if (stop == Stop::EBREAK) {
    if (retval == 0) {
      Log("\033[32mHit GOOD trap.\033[0m\n");
    } else {
      Log("\033[31mHit  BAD trap.\033[0m\n");
    }
  } else if (stop == Stop::DIFFTEST_FAILED) {
    Log("\033[31mDifftest failed.\033[0m\n");
  } else if (stop == Stop::CPU_HANG) {
    Log("\033[31mCPU hangs.\033[0m\n");
  } else if (stop == Stop::DEBUG) {
    Log("\033[31mDebug Stop.\033[0m\n");
  }

#ifdef NVBOARD
  nvboard_quit();
#endif
  // printPerfCounters();
  sim_speed.printSimulationSpeed(totalCycles);
  auto good = (stop == Stop::EBREAK) && (retval == 0);
  // * Clean up
  close_log();
  close_difftest();
  top->final();
  delete top;
  return !good;
}