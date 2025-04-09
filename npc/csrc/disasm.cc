// /***************************************************************************************
//  * Copyright (c) 2014-2022 Zihao Yu, Nanjing University
//  *
//  * NEMU is licensed under Mulan PSL v2.
//  * You can use this software according to the terms and conditions of the
//  Mulan *PSL v2. You may obtain a copy of Mulan PSL v2 at:
//  *          http://license.coscl.org.cn/MulanPSL2
//  *
//  * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY
//  *KIND, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
//  *NON-INFRINGEMENT, MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
//  *
//  * See the Mulan PSL v2 for more details.
//  ***************************************************************************************/

// #if defined(__GNUC__) && !defined(__clang__)
// #pragma GCC diagnostic push
// #pragma GCC diagnostic ignored "-Wmaybe-uninitialized"
// #endif

// #include "llvm/MC/MCAsmInfo.h"
// #include "llvm/MC/MCContext.h"
// #include "llvm/MC/MCDisassembler/MCDisassembler.h"
// #include "llvm/MC/MCInstPrinter.h"
// #if LLVM_VERSION_MAJOR >= 14
// #include "llvm/MC/TargetRegistry.h"
// #if LLVM_VERSION_MAJOR >= 15
// #include "llvm/MC/MCSubtargetInfo.h"
// #endif
// #else
// #include "llvm/Support/TargetRegistry.h"
// #endif
// #include "llvm/Support/TargetSelect.h"

// #if defined(__GNUC__) && !defined(__clang__)
// #pragma GCC diagnostic pop
// #endif

// #if LLVM_VERSION_MAJOR < 11
// #error Please use LLVM with major version >= 11
// #endif

// using namespace llvm;

// static llvm::MCDisassembler *gDisassembler = nullptr;
// static llvm::MCSubtargetInfo *gSTI = nullptr;
// static llvm::MCInstPrinter *gIP = nullptr;

// void init_disasm(const char *triple) {
//   llvm::InitializeAllTargetInfos();
//   llvm::InitializeAllTargetMCs();
//   llvm::InitializeAllAsmParsers();
//   llvm::InitializeAllDisassemblers();

//   std::string errstr;
//   std::string gTriple(triple);

//   llvm::MCInstrInfo *gMII = nullptr;
//   llvm::MCRegisterInfo *gMRI = nullptr;
//   auto target = llvm::TargetRegistry::lookupTarget(gTriple, errstr);
//   if (!target) {
//     llvm::errs() << "Can't find target for " << gTriple << ": " << errstr
//                  << "\n";
//     assert(0);
//   }

//   MCTargetOptions MCOptions;
//   gSTI = target->createMCSubtargetInfo(gTriple, "", "");
//   std::string isa = target->getName();
//   if (isa == "riscv32" || isa == "riscv64") {
//     gSTI->ApplyFeatureFlag("+m");
//     gSTI->ApplyFeatureFlag("+a");
//     gSTI->ApplyFeatureFlag("+c");
//     gSTI->ApplyFeatureFlag("+f");
//     gSTI->ApplyFeatureFlag("+d");
//   }
//   gMII = target->createMCInstrInfo();
//   gMRI = target->createMCRegInfo(gTriple);
//   auto AsmInfo = target->createMCAsmInfo(*gMRI, gTriple, MCOptions);
// #if LLVM_VERSION_MAJOR >= 13
//   auto llvmTripleTwine = Twine(triple);
//   auto llvmtriple = llvm::Triple(llvmTripleTwine);
//   auto Ctx = new llvm::MCContext(llvmtriple, AsmInfo, gMRI, nullptr);
// #else
//   auto Ctx = new llvm::MCContext(AsmInfo, gMRI, nullptr);
// #endif
//   gDisassembler = target->createMCDisassembler(*gSTI, *Ctx);
//   gIP = target->createMCInstPrinter(llvm::Triple(gTriple),
//                                     AsmInfo->getAssemblerDialect(), *AsmInfo,
//                                     *gMII, *gMRI);
//   gIP->setPrintImmHex(true);
//   gIP->setPrintBranchImmAsAddress(true);
//   if (isa == "riscv32" || isa == "riscv64")
//     gIP->applyTargetSpecificCLOption("no-aliases");
// }

// void disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte)
// {
//   MCInst inst;
//   llvm::ArrayRef<uint8_t> arr(code, nbyte);
//   uint64_t dummy_size = 0;
//   gDisassembler->getInstruction(inst, dummy_size, arr, pc, llvm::nulls());

//   std::string s;
//   raw_string_ostream os(s);
//   gIP->printInst(&inst, pc, "", *gSTI, os);

//   int skip = s.find_first_not_of('\t');
//   const char *p = s.c_str() + skip;
//   assert((int)s.length() - skip < size);
//   strcpy(str, p);
// }

/***************************************************************************************
 * Copyright (c) 2014-2024 Zihao Yu, Nanjing University
 *
 * NEMU is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan
 *PSL v2. You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY
 *KIND, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 *NON-INFRINGEMENT, MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 *
 * See the Mulan PSL v2 for more details.
 ***************************************************************************************/

#include <capstone/capstone.h>
#include <cassert>
#include <dlfcn.h>
#include <string>

static size_t (*cs_disasm_dl)(csh handle, const uint8_t *code, size_t code_size,
                              uint64_t address, size_t count, cs_insn **insn);
static void (*cs_free_dl)(cs_insn *insn, size_t count);

static csh handle;

// * function types
typedef cs_err (*cs_open_dl_t)(cs_arch arch, cs_mode mode, csh *handle);
typedef size_t (*cs_disasm_dl_t)(csh handle, const uint8_t *code,
                                 size_t code_size, uint64_t address,
                                 size_t count, cs_insn **insn);
typedef void (*cs_free_dl_t)(cs_insn *insn, size_t count);

typedef cs_err (*cs_option_t)(csh handle, cs_opt_type type, size_t value);

void init_disasm() {
  void *dl_handle;
  std::string dl_path = std::getenv("NEMU_HOME") +
                        std::string("/tools/capstone/repo/libcapstone.so.5");
  dl_handle = dlopen(dl_path.c_str(), RTLD_LAZY);
  assert(dl_handle);

  cs_err (*cs_open_dl)(cs_arch arch, cs_mode mode, csh *handle) = NULL;
  cs_open_dl = (cs_open_dl_t)dlsym(dl_handle, "cs_open");
  assert(cs_open_dl);

  cs_disasm_dl = (cs_disasm_dl_t)dlsym(dl_handle, "cs_disasm");
  assert(cs_disasm_dl);

  cs_free_dl = (cs_free_dl_t)dlsym(dl_handle, "cs_free");
  assert(cs_free_dl);

  cs_arch arch = CS_ARCH_RISCV;
  cs_mode mode = CS_MODE_RISCV32;
  int ret = cs_open_dl(arch, mode, &handle);
  assert(ret == CS_ERR_OK);
}

void disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte) {
  cs_insn *insn;
  size_t count = cs_disasm_dl(handle, code, nbyte, pc, 0, &insn);
  if (count == 0) {
    snprintf(str, size, "[ILLEGAL INSTRUCTION]");
    return;
  } else {
    int ret = snprintf(str, size, "%s", insn->mnemonic);
    if (insn->op_str[0] != '\0') {
      snprintf(str + ret, size - ret, "\t%s", insn->op_str);
    }
  }
  cs_free_dl(insn, count);
}