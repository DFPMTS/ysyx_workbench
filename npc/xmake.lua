target("Vtop")
  add_rules("verilator.binary")
  set_toolchains("@verilator")  

  add_cxxflags("-fPIE")
  add_cxxflags("-g")
  on_config(function (target) 
    local llvm_cxxflags = os.iorun("llvm-config --cxxflags")
    local llvm_ldflags = os.iorun("llvm-config --libs")
    target:add("cxxflags",llvm_cxxflags,"-fPIE")
    target:add("ldflags",llvm_ldflags)
    local sdl2_cxxflags = os.iorun("sdl2-config --cflags")
    local sdl2_ldflags = os.iorun("sdl2-config --libs") .. "-lSDL2_image -lSDL2_ttf"
    target:add("cxxflags",sdl2_cxxflags)
    target:add("ldflags",sdl2_ldflags)
  end)

  add_files("csrc/*.cpp")
  add_files("csrc/*.cc")
  if get_config("sim_target") == "npc" then
    add_values("verilator.flags", "--trace-fst")
    add_values("verilator.flags", "-sv")
  else 
    add_values("verilator.flags", "--trace-fst", "--timescale", "1ns/1ps", "--no-timing", "--top-module", "ysyxSoCFull", "--autoflush", "-Wno-WIDTHEXPAND")
    add_values("verilator.flags","-I../ysyxSoC/perip/uart16550/rtl")
    add_values("verilator.flags","-I../ysyxSoC/perip/spi/rtl")
    add_files("../ysyxSoC/perip/**.v")
    add_files("../ysyxSoC/build/ysyxSoCFull.v")
  end 
  
  if get_config("sim_target") == "nvboard" then
    add_includedirs("/home/dfpmts/Documents/ysyx-workbench/nvboard/include")
    add_files("constr/auto_bind.cpp")
    add_links("/home/dfpmts/Documents/ysyx-workbench/nvboard/build/nvboard.a")
  end    

  add_files("vsrc/*.sv")
  add_files("vsrc/*.v")
  add_includedirs("$(buildir)")
  add_includedirs("include")
  add_options("itrace", "mtrace", "ftrace", "Waveform", "Difftest", "sim_target")
  add_configfiles("csrc/config.h.in")

target("FreeList_tb")  
  add_rules("verilator.binary")
  set_toolchains("@verilator")

  add_values("verilator.flags", "--trace-fst")

  add_files("testbench/FreeList_tb.cpp")
  add_files("vsrc/FreeList.sv")  
  add_includedirs("$(buildir)")

target("testRename_tb")  
  add_rules("verilator.binary")
  set_toolchains("@verilator")

  add_values("verilator.flags", "--trace-fst")

  add_files("testbench/testRename_tb.cpp")
  add_files("vsrc/*.sv")  
  add_includedirs("$(buildir)")


target("chisel")  
  set_kind("phony")
  on_build(function (target) 
    os.exec("make gen_verilog SIM_TARGET=$(sim_target) MODE=$(Mode)")
  end)

option("sim_target")
  set_description("Simulation target")
  set_values("npc", "soc", "nvboard")
  after_check(function (option)
    if option:value() == "npc" then
      option:set("configvar", "NPC", true)
    end
    if option:value() == "nvboard" then
      option:set("configvar", "NVBOARD", true)
    end
  end)

option("Difftest")
  set_description("Enable difftest")
  set_configvar("DIFFTEST", true)
  set_default(false)  

option("itrace")
  set_description("Enable instruction trace (itrace)")
  set_configvar("ITRACE", true)
  set_default(false)  
  set_category("Trace")

option("mtrace")
  set_description("Enable memory trace (mtrace)")
  set_configvar("MTRACE", true)
  set_default(false)
  set_category("Trace")

option("ftrace")
  set_description("Enable function trace (ftrace)")
  set_configvar("FTRACE", true)
  set_default(false)
  set_category("Trace")

option("Waveform")
  set_description("Enable waveform generation")
  set_configvar("WAVE", true)
  set_default(false)

option("Mode")
  set_description("Debug / Evaluation")
  set_values("Debug", "Evaluation")
  after_check(function (option)
    if option:value() == "Debug" then
      option:set("configvar", "DEBUG", true)
    end    
  end)