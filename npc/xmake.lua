target("Vtop")
  add_rules("verilator.binary")
  set_toolchains("@verilator")  

  add_cxxflags("-fPIE")
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
    add_values("verilator.flags", "--trace")
  else 
    add_values("verilator.flags", "--trace", "--timescale", "1ns/1ps", "--no-timing", "--top-module", "ysyxSoCFull","--autoflush")
    add_values("verilator.flags","-I../ysyxSoC/perip/uart16550/rtl")
    add_values("verilator.flags","-I../ysyxSoC/perip/spi/rtl")
    add_files("../ysyxSoC/perip/**.v")
    add_files("../ysyxSoC/build/ysyxSoCFull.v")
  end 

  add_includedirs("/home/dfpmts/Documents/ysyx-workbench/nvboard/include")
  add_files("constr/auto_bind.cpp")
  add_links("/home/dfpmts/Documents/ysyx-workbench/nvboard/build/nvboard.a")

  add_files("vsrc/*.sv")
  add_files("vsrc/*.v")
  add_includedirs("$(buildir)")
  add_includedirs("include")
  add_options("ITrace", "MTrace", "Waveform", "Difftest", "sim_target")
  add_configfiles("csrc/config.h.in")

target("chisel")  
  set_kind("phony")
  on_build(function (target) 
    os.exec("make gen_verilog SIM_TARGET=$(sim_target)")
  end)

option("sim_target")
  set_description("Simulation target")
  set_values("npc", "soc")
  after_check(function (option)
    if option:value() == "npc" then
      option:set("configvar", "NPC", true)
    end
  end)

option("Difftest")
  set_description("Enable difftest")
  set_configvar("DIFFTEST", true)
  set_default(false)  

option("ITrace")
  set_description("Enable instruction trace")
  set_configvar("ITRACE", true)
  set_default(false)  
  set_category("Trace")

option("MTrace")
  set_description("Enable memory trace")
  set_configvar("MTRACE", true)
  set_default(false)
  set_category("Trace")

option("Waveform")
  set_description("Enable waveform generation")
  set_configvar("WAVE", true)
  set_default(false)