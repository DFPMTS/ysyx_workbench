#include "EventMonitor.hpp"

static uint64_t eventCount[64];

extern "C" {
void monitorEvent(int eventId, int enable) { eventCount[eventId] += enable; }
}

// // IFU
// def ifuFinished = 0.U(Config.eventIdWidth)
// def ifuStalled  = 1.U(Config.eventIdWidth)

// // IDU
// def iduBruInst = 2.U(Config.eventIdWidth)
// def iduAluInst = 3.U(Config.eventIdWidth)
// def iduMemInst = 4.U(Config.eventIdWidth)
// def iduCsrInst = 5.U(Config.eventIdWidth)

// // EXU

// // MEM
// def memFinished = 6.U(Config.eventIdWidth)
// def memStalled  = 7.U(Config.eventIdWidth)

std::string getEventName(int eventId) {
  switch (eventId) {
  case 0:
    return "ifuFinished";
  case 1:
    return "ifuStalled";
  case 2:
    return "iduBruInst";
  case 3:
    return "iduAluInst";
  case 4:
    return "iduMemInst";
  case 5:
    return "iduCsrInst";
  case 6:
    return "memFinished";
  case 7:
    return "memStalled";
  default:
    return "unknown";
  }
}

int getEventId(const std::string &eventName) {
  if (eventName == "ifuFinished") {
    return 0;
  } else if (eventName == "ifuStalled") {
    return 1;
  } else if (eventName == "iduBruInst") {
    return 2;
  } else if (eventName == "iduAluInst") {
    return 3;
  } else if (eventName == "iduMemInst") {
    return 4;
  } else if (eventName == "iduCsrInst") {
    return 5;
  } else if (eventName == "memFinished") {
    return 6;
  } else if (eventName == "memStalled") {
    return 7;
  } else if (eventName == "icacheMiss") {
    return 8;
  } else if (eventName == "totalBranch") {
    return 9;
  } else if (eventName == "branchMisPred") {
    return 10;
  } else {
    return -1;
  }
}

uint64_t getEventCount(int eventId) { return eventCount[eventId]; }

uint64_t getEventCount(const std::string &eventName) {
  int eventId = getEventId(eventName);
  if (eventId == -1) {
    return 0;
  } else {
    return getEventCount(eventId);
  }
}

void clearAllEventCount() {
  for (int i = 0; i < 64; ++i) {
    eventCount[i] = 0;
  }
}

static bool commit = false;

extern "C" {
void setCommit(uint32_t isCommit) { commit = isCommit; }
}

bool isCommit() { return commit; }