include $(AM_HOME)/scripts/isa/riscv.mk
include $(AM_HOME)/scripts/platform/npc.mk
COMMON_CFLAGS += -march=rv32ima_zicsr_zifencei -mabi=ilp32  # overwrite
LDFLAGS       += -melf32lriscv                    # overwrite