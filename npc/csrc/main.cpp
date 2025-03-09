#include "EventMonitor.hpp"
#include "SimState.hpp"
#include "Uop.hpp"
#include "cpu.hpp"
#include "debug.hpp"
#include "difftest.hpp"
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

static uint64_t totalCycles = 0;

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

int main(int argc, char *argv[]) {
  // Verilated::commandArgs(argc, argv);
  gpr = [&](int index) { return state.getReg(index); };
  PC = [&]() { return state.getPC(); };
  init_monitor(argc, argv);
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
  signal(SIGINT, [](int) {
    puts("Vtop: SIGINT received");
    running.store(false);
  });
  while (running.load()) {
    // if (state.getInstRetired() > 555331000) {
    // begin_wave = true;
    // }
    cpu_step();
    state.log(totalCycles);
    ++totalCycles;
  }
  std::cerr << "Simulation End" << std::endl;
  printf("Total cycles:\t %lu\n", totalCycles);
  printf("Total instructions:\t %lu\n", state.getInstRetired());
  state.printCommited();
  state.printInsts();
  state.printArchRenameTable();
  get_context(&dut);
  isa_reg_display(&dut);
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
  }
#ifdef NVBOARD
  nvboard_quit();
#endif
  printPerfCounters();
  sim_speed.printSimulationSpeed(totalCycles);
  auto good = (stop == Stop::EBREAK) && (retval == 0);
  return !good;
}