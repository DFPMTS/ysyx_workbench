include $(AM_HOME)/scripts/isa/loongarch32r.mk

CFLAGS  += -DISA_H=\"loongarch/loongarch32r.h\"

AM_SRCS += loongarch/nemu/start.S \
           loongarch/nemu/cte.c \
           loongarch/nemu/trap.S \
           loongarch/nemu/vme.c \
					 loongarch/nemu/trm.c \
					 loongarch/nemu/ioe.c \
					 loongarch/nemu/timer.c

CFLAGS    += -fdata-sections -ffunction-sections
LDFLAGS   += -T $(AM_HOME)/scripts/linker.ld \
						 --defsym=_pmem_start=0x00000000 --defsym=_entry_offset=0x1c000000
LDFLAGS   += --gc-sections -e _start
CFLAGS += -DMAINARGS=\"$(mainargs)\"
CFLAGS += -I$(AM_HOME)/am/src/platform/nemu/include
.PHONY: $(AM_HOME)/am/src/riscv/npc/trm.c

NPCFLAGS += $(IMAGE).bin
NPCFLAGS += -l $(shell dirname $(IMAGE).elf)/npc-log.txt 
NPCFLAGS += -e $(IMAGE).elf

image: $(IMAGE).elf
	@$(OBJDUMP) -d $(IMAGE).elf > $(IMAGE).txt
	@echo + OBJCOPY "->" $(IMAGE_REL).bin
	@$(OBJCOPY) -S --set-section-flags .bss=alloc,contents -O binary $(IMAGE).elf $(IMAGE).bin

run: image
	$(MAKE) -C $(NPC_HOME) MAINARGS="$(NPCFLAGS)" sim