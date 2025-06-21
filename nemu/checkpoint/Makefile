
CROSS_COMPILE = riscv64-unknown-linux-gnu-

CFLAGS = -march=rv32ima_zicsr -mabi=ilp32 

cpt.bin: cpt.o
	$(CROSS_COMPILE)objcopy -O binary cpt.o cpt.bin

cpt.o: cpt.S
	$(CROSS_COMPILE)gcc $(CFLAGS) -c -o cpt.o cpt.S

