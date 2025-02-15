INC_DIR = $(abspath ./include)
VSRC_DIR = ./vsrc
CORE_SRC_DIR = ./Core/src

export PATH := $(PATH):$(abspath ./utils)

TOPNAME = top
VERILATOR = verilator
VERILATOR_CFLAGS += -MMD --build --cc \
					-O3 --x-assign fast --x-initial fast --noassert --trace
CXXFLAGS += -DTOP_NAME="\"V$(TOPNAME)\"" $(shell llvm-config --cxxflags) -fPIE -I$(INC_DIR)
LDFLAGS = $(shell llvm-config --libs)
					
BUILD_DIR = ./build
OBJ_DIR = $(BUILD_DIR)/obj_dir
BIN = $(BUILD_DIR)/$(TOPNAME)

$(shell mkdir -p $(BUILD_DIR))

CORE_SRCS = $(shell find $(abspath $(CORE_SRC_DIR)) -name "*.scala")
VSRCS = $(shell find $(abspath $(VSRC_DIR)) -name "*.v" -or -name "*.sv")
VSRCS_FLAG = $(VSRC_DIR)/.vsrc_flag
CSRCS = $(shell find $(abspath ./csrc) -name "*.c" -or -name "*.cc" -or -name "*.cpp")
CINCS = $(shell find $(abspath ./include) -name "*.h" -or -name "*.hpp")
test:
	./mill -i __.test

gen_verilog: $(VSRCS_FLAG)$(SIM_TARGET)$(MODE)

$(VSRCS_FLAG)$(SIM_TARGET)$(MODE): $(CORE_SRCS)
	make verilog
	python3 ./verilator_public.py
	touch $@

$(BIN): $(VSRCS_FLAG) $(CSRCS) $(CINCS)
	rm -rf $(OBJ_DIR)
	$(VERILATOR) $(VERILATOR_CFLAGS) \
		--top-module $(TOPNAME) $(VSRCS) $(CSRCS) \
		$(addprefix -CFLAGS , $(CXXFLAGS)) $(addprefix -LDFLAGS , $(LDFLAGS)) \
		--Mdir $(OBJ_DIR) --exe -o $(abspath $(BIN))

verilog:
	$(call git_commit, "generate verilog")	
	-rm -rf $(VSRC_DIR)
	mkdir -p $(VSRC_DIR)
	./mill -i __.test.runMain Elaborate_$(SIM_TARGET) $(SIM_TARGET) $(MODE)

help:
	./mill -i __.test.runMain Elaborate --help

compile:
	./mill -i __.compile

bsp:
	./mill -i ./mill.bsp.BSP/install

idea:
	./mill -i ./mill.idea.GenIdea/idea

reformat:
	./mill -i __.reformat

checkformat:
	./mill -i __.checkFormat

clean:
	-rm -rf $(BUILD_DIR)

.PHONY: test verilog help compile bsp reformat checkformat clean gen_verilog

override MAINARGS += --diff=$(NEMU_HOME)/build/riscv32-nemu-interpreter-so

# sim: $(BIN)
#   $(call git_commit, "sim RTL") # DO NOT REMOVE THIS LINE!!!
# 	echo $(MAINARGS)
# 	$^ $(MAINARGS)

NXDC_FILES = $(abspath constr/top.nxdc)
SRC_AUTO_BIND = $(abspath constr/auto_bind.cpp)
$(SRC_AUTO_BIND): $(NXDC_FILES)
	python3 $(NVBOARD_HOME)/scripts/auto_pin_bind.py $^ $@

nxdc: $(SRC_AUTO_BIND)


sim: 	
	xmake build chisel
	xmake build Vtop
	$(call git_commit, "sim RTL") # DO NOT REMOVE THIS LINE!!!
	xmake run Vtop $(MAINARGS)
	
config:
	xmake f --menu

xmake_test: 
	xmake build chisel
	xmake build Vtop
	xmake run Vtop --log log.txt --diff $(NEMU_HOME)/build/riscv32-nemu-interpreter-so

soc_test_print: soc_test_print.c
	riscv64-linux-gnu-gcc -march=rv32e -mabi=ilp32e -O2 -c ./soc_test_print.c -o test.o
	riscv64-linux-gnu-objcopy -O binary test.o test.bin
	xmake build chisel
	xmake build Vtop
	xmake run Vtop `realpath test.bin` --log log.txt --diff $(NEMU_HOME)/build/riscv32-nemu-interpreter-so

FreeList_tb:
	xmake build chisel
	xmake build FreeList_tb
	xmake run FreeList_tb

testRename_tb:
	xmake build chisel
	xmake build testRename_tb
	xmake run testRename_tb

sta:
	make -C ../../yosys-sta sta DESIGN=multi SDC_FILE=$(abspath npc.sdc) RTL_FILES="$(shell find $(abspath ./vsrc) -regex ".*\.\(sv\|v\)")" CLK_FREQ_MHZ=5000

-include ../Makefile
