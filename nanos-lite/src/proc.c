#include <proc.h>

#define MAX_NR_PROC 4

static PCB pcb[MAX_NR_PROC] __attribute__((used)) = {};
static PCB pcb_boot = {};
PCB *current = NULL;

void naive_uload(PCB *pcb, const char *filename);

void switch_boot_pcb() {
  current = &pcb_boot;
}

void hello_fun(void *arg) {
  int j = 1;
  int k = 1;  
  while (1) {
    if (k == 10000){
      Log("Hello World from Nanos-lite with arg '%s' for the %dth time!",
          (char *)arg, j);
      j++;
      k = 1;
    }else{
      k++;
    }
    yield();
  }
}

void context_kload(PCB *pcb, void *entry, void *arg);
int context_uload(PCB *pcb, const char *filename, char *const argv[],
                   char *const envp[]);

static void write_sscratch(uintptr_t sp)
{
  asm volatile("csrw sscratch, %[sp]" ::[sp] "r"(sp));
}

void init_proc() {
  printf("pcb_boot: %p pcb[0]: %p pcb[1]: %p pcb[2]: %p pcb[3]: %p\n",
         &pcb_boot, pcb, pcb + 1, pcb + 2, pcb + 3);
  switch_boot_pcb();
  write_sscratch(0);
  // context_kload(&pcb[0], hello_fun, "Goodbye");
  // context_kload(&pcb[1], hello_fun, "World");
  // context_uload(&pcb[0], "/bin/pal", (char *[]){"/bin/pal", "--skip", NULL},
  //               (char *[]){NULL});
  context_uload(&pcb[0], "/bin/hello", (char *[]){"/bin/hello", "--skip", NULL},
                (char *[]){NULL});
  context_uload(&pcb[1], "/bin/hello", (char *[]){"/bin/hello", NULL},
                (char *[]){NULL});
  // context_uload(&pcb[1], "/bin/pal", (char *[]){"/bin/pal", NULL},
  //               (char *[]){NULL});

  Log("Initializing processes...");

  // load program here
  // naive_uload(NULL, "/bin/nterm");
}

void __am_get_cur_as(Context *c);
void __am_switch(Context *c);

Context *schedule(Context *prev) {
  // update current PCB's cp
  current->cp = prev;
  __am_get_cur_as(prev);
  // choose next PCB to run
  if (current == &pcb_boot) {
    current = &pcb[1];
  } else if (current == &pcb[0]) {
    current = &pcb[1];
  } else {
    current = &pcb[0];
  }
  __am_switch(current->cp);
  return current->cp;
}