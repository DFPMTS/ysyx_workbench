#ifndef STATUS_HPP
#define STATUS_HPP

#include <atomic>

// * give the stop reason
enum class Stop { EBREAK, DIFFTEST_FAILED, CPU_HANG, INTERRUPT };

extern std::atomic_bool running;
extern Stop stop;

#endif
