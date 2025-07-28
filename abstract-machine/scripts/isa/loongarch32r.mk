CROSS_COMPILE := loongarch32r-linux-gnusf-
COMMON_FLAGS  := -fno-pic
CFLAGS        += $(COMMON_FLAGS) -static
ASFLAGS       += $(COMMON_FLAGS) -O0

# overwrite ARCH_H defined in $(AM_HOME)/Makefile
ARCH_H := arch/loongarch32r-nemu.h
