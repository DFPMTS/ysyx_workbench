import chisel3._
import chisel3.util._
import utils._

object CSRList {
  // Define CSR addresses as a Map
  val csrMap: Map[String, UInt] = Map(
    "sstatus"    -> 0x100.U,
    "sie"        -> 0x104.U,
    "stvec"      -> 0x105.U,
    "scounteren" -> 0x106.U,

    "sscratch"   -> 0x140.U,
    "sepc"       -> 0x141.U,
    "scause"     -> 0x142.U,
    "stval"      -> 0x143.U,
    "sip"        -> 0x144.U,
    "satp"       -> 0x180.U,

    "mstatus"    -> 0x300.U,
    "misa"       -> 0x301.U,
    "medeleg"    -> 0x302.U,
    "mideleg"    -> 0x303.U,
    "mie"        -> 0x304.U,
    "mtvec"      -> 0x305.U,
    "mcounteren" -> 0x306.U,
    "menvcfg"    -> 0x30A.U,
    "mstatush"   -> 0x310.U,
    "menvcfgh"   -> 0x31A.U,
    "mscratch"   -> 0x340.U,
    "mepc"       -> 0x341.U,
    "mcause"     -> 0x342.U,
    "mtval"      -> 0x343.U,
    "mip"        -> 0x344.U,

    "time"       -> 0xC01.U,
    "timeh"      -> 0xC81.U,

    "mvendorid"  -> 0xF11.U,
    "marchid"    -> 0xF12.U,
    "mipid"      -> 0xF13.U,
    "mhartid"    -> 0xF14.U
  )

  def apply(name: String): UInt = csrMap(name)
  // Function to check if a value matches any CSR
  def exists(value: UInt): Bool = csrMap.map(_._2 === value).reduce(_ || _)
}

object Priv {
  val M = 3.U(2.W)
  val S = 1.U(2.W)
  val U = 0.U(2.W)
}

class Mstatus extends CoreBundle {
  val SD      = UInt(1.W) /* 31:31 */
  val _WPRI_3 = UInt(8.W) /* 30:23 */
  val TSR     = UInt(1.W) /* 22:22 */
  val TW      = UInt(1.W) /* 21:21 */
  val TVM     = UInt(1.W) /* 20:20 */
  val MXR     = UInt(1.W) /* 19:19 */
  val SUM     = UInt(1.W) /* 18:18 */
  val MPRV    = UInt(1.W) /* 17:17 */
  val XS      = UInt(2.W) /* 16:15 */
  val FS      = UInt(2.W) /* 14:13 */
  val MPP     = UInt(2.W) /* 12:11 */
  val VS      = UInt(2.W) /* 10:9 */
  val SPP     = UInt(1.W) /*  8:8 */
  val MPIE    = UInt(1.W) /*  7:7 */
  val UBE     = UInt(1.W) /*  6:6 */
  val SPIE    = UInt(1.W) /*  5:5 */
  val _WPRI_2 = UInt(1.W) /*  4:4 */
  val MIE     = Bool()    /*  3:3 */
  val _WPRI_1 = UInt(1.W) /*  2:2 */
  val SIE     = Bool()    /*  1:1 */
  val _WPRI_0 = UInt(1.W) /*  0:0 */
}

class VMCSR extends CoreBundle {
  val mode     = UInt(1.W)
  val rootPPN  = UInt(PAGE_NR_LEN.W)
  val epm      = UInt(2.W) // * Effective Privilege Mode
  val priv     = UInt(2.W)
  val mxr      = Bool()
  val sum      = Bool()
}

class TrapCSR extends CoreBundle {
  val mtvec   = UInt(XLEN.W)
  val mepc    = UInt(XLEN.W)  
  val stvec   = UInt(XLEN.W)
  val sepc    = UInt(XLEN.W)
  val mideleg = UInt(XLEN.W)
  val medeleg = UInt(XLEN.W)
  val priv    = UInt(2.W)

  val interrupt      = Bool()
  val interruptCause = UInt(4.W)
  val interruptDeleg = Bool()
}

class CSRIO extends CoreBundle {
  val IN_readRegUop  = Flipped(Decoupled(new ReadRegUop))
  val OUT_writebackUop  = Valid(new WritebackUop)
  val IN_CSRCtrl = Flipped(new CSRCtrl)
  val OUT_VMCSR = new VMCSR
  val OUT_trapCSR = new TrapCSR
  val IN_mtime = Input(UInt(64.W))
  val IN_MTIP = Flipped(Bool())
  val IN_xtvalRec = Flipped(Valid(new XtvalRec))
}

/*typedef union {
  struct {
    uint32_t _ZERO_0 : 5;
    uint32_t STI : 1;
    uint32_t _ZERO_1 : 1;
    uint32_t MTI : 1;
    uint32_t _ZERO_2 : 24;
  };
  uint32_t val;
} Mipe; */

class Mipe extends CoreBundle {
  val _ZERO_4 = UInt(24.W)
  val MTI = Bool()
  val _ZERO_3 = UInt(1.W)
  val STI = Bool()
  val _ZERO_2 = UInt(1.W)
  val MSI = Bool()
  val _ZERO_1 = UInt(1.W)
  val SSI = Bool()
  val _ZERO_0 = UInt(1.W)
}

class Menvcfg extends CoreBundle {
  val _ZERO_1 = UInt(31.W)
  val FIOM    = UInt(1.W)
}

class CSR extends CoreModule {
  val io = IO(new CSRIO)

  val priv    = RegInit(3.U(2.W))  

  // * sstatus    0x100
  // * sie        0x104
  val stvec        = Reg(UInt(XLEN.W))                  // * 0x105
  // * scounteren (read-only zero) 0x106
  val sscratch     = Reg(UInt(XLEN.W))                  // * 0x140
  val sepc         = Reg(UInt(XLEN.W))                  // * 0x141
  val scause       = Reg(UInt(XLEN.W))                  // * 0x142
  val stval        = Reg(UInt(XLEN.W))                  // * 0x143
  // * sip        0x144
  val satp         = RegInit(0.U(32.W))                 // * 0x180

  val mstatus      = RegInit(0.U.asTypeOf(new Mstatus)) // * 0x300
  val mstatusU     = WireInit(mstatus.asUInt)
  dontTouch(mstatusU)

  val misa         = RegInit(((1L << 30) | (1 << 0) | (1 << 2) | (1 << 8) | (1 << 12) | (1 << 18) | (1 << 20)).U(32.W)) // * 0x301
  val medeleg      = RegInit(0.U(XLEN.W))               // * 0x302
  val mideleg      = RegInit(0.U(XLEN.W))               // * 0x303
  val mie          = RegInit(0.U.asTypeOf(new Mipe))    // * 0x304
  val mieU         = WireInit(mie.asUInt)
  dontTouch(mieU)

  val mtvec        = Reg(UInt(32.W))                    // * 0x305
  // * mcounteren (read-only zero) 0x306
  val menvcfg      = RegInit(0.U.asTypeOf(new Menvcfg)) // * 0x30A
  val menvcfgU     = WireInit(menvcfg.asUInt)
  dontTouch(menvcfgU)
  // * mstatush (read-only zero)  0x310
  // * menvcfgh (read-only zero)  0x31A

  val mscratch     = Reg(UInt(XLEN.W))                  // * 0x340
  val mepc         = Reg(UInt(XLEN.W))                  // * 0x341
  val mcause       = Reg(UInt(XLEN.W))                  // * 0x342
  val mtval        = Reg(UInt(XLEN.W))                  // * 0x343
  val mip          = RegInit(0.U.asTypeOf(new Mipe))    // * 0x344
  val mipU         = WireInit(mip.asUInt)
  dontTouch(mipU)

  // * time  (CLINT-mtime)  0xC01
  // * timeh (CLINT-mtimeh) 0xC81

  // * all hardwired
  // * mvendorid  0xF11
  // * marchid    0xF12
  // * mipid      0xF13
  // * mhartid    0xF14

  val inValid = io.IN_readRegUop.valid
  val inUop = io.IN_readRegUop.bits
  val addr = io.IN_readRegUop.bits.imm(11, 0)
  val ren  = inValid
  val wen  = inValid && inUop.opcode =/= CSROp.CSRR
  val rdata = Wire(UInt(XLEN.W))
  val wdata = WireDefault(inUop.src1)
  when(inUop.opcode === CSROp.CSRRW) {
    wdata := inUop.src1
  }.elsewhen(inUop.opcode === CSROp.CSRRS) {
    wdata := rdata | inUop.src1
  }.elsewhen(inUop.opcode === CSROp.CSRRC) {
    wdata := rdata & ~inUop.src1
  }.elsewhen(inUop.opcode === CSROp.CSRRWI) {
    wdata := inUop.imm(16, 12)
  }.elsewhen(inUop.opcode === CSROp.CSRRSI) {
    wdata := rdata | Cat(0.U(27.W), inUop.imm(16, 12))
  }.elsewhen(inUop.opcode === CSROp.CSRRCI) {
    wdata := rdata & ~Cat(0.U(27.W), inUop.imm(16, 12))
  }
  /* 
  ! This does now work! the imm(16, 12) does not zero-extend before "~"
  ! So need to zero-extend manually
  .elsewhen(inUop.opcode === CSROp.CSRRCI) {
    wdata := rdata & ~inUop.imm(16, 12)
  } */
  // ! also check mret/sret, it maybe illegal inst
  val privError = addr(9, 8) > priv
  val roError   = wen && addr(11, 10) === 3.U
  val notExist  = !CSRList.exists(addr)
  val illegal = privError || roError || notExist
  val doRead = ren && !illegal
  val doWrite = wen && !illegal

  val trapValid = io.IN_CSRCtrl.trap
  val trapIntr  = io.IN_CSRCtrl.intr
  val trapCause = io.IN_CSRCtrl.cause
  val trapPC    = io.IN_CSRCtrl.pc
  val mret      = io.IN_CSRCtrl.mret
  val sret      = io.IN_CSRCtrl.sret

  val uop = Reg(new WritebackUop)
  val uopValid = RegInit(false.B)

  val xtval = Mux(trapIntr, 0.U, 
                  Mux(trapCause === 12.U, 
                      trapPC, 
                      Mux(io.IN_xtvalRec.valid, io.IN_xtvalRec.bits.tval, 0.U)))

  when(trapValid) {
    // printf("------------trap: %x epc: %x\n",trapCause, trapPC)
    when(io.IN_CSRCtrl.delegate) {
      // printf("--------------------------delegate\n")
      // printf("--------------------------priv: %x\n",priv)
      scause := Cat(trapIntr, 0.U(31.W)) | trapCause
      sepc := trapPC
      stval := xtval
      mstatus.SPIE := mstatus.SIE
      mstatus.SIE := 0.U
      mstatus.SPP := priv
      priv := Priv.S
    }.otherwise {      
      mcause := Cat(trapIntr, 0.U(31.W)) | trapCause
      mepc := trapPC
      mtval := xtval
      mstatus.MPIE := mstatus.MIE
      mstatus.MIE := 0.U
      mstatus.MPP := priv
      priv := Priv.M    
    }
  }

  when(mret) {
    // printf("--------------------------mret\n")
    when(mstatus.MPP < Priv.M) {
      mstatus.MPRV := 0.U
    }
    mstatus.MIE := mstatus.MPIE
    mstatus.MPIE := 1.U
    mstatus.MPP := Priv.U
    priv := mstatus.MPP
  }

  when(sret) {
    // printf("--------------------------sret\n")
    when(mstatus.SPP < Priv.M) {
      mstatus.MPRV := 0.U
    }
    mstatus.SIE := mstatus.SPIE
    mstatus.SPIE := 1.U
    mstatus.SPP := Priv.U
    priv := mstatus.SPP
  }

  rdata := 0.U
  when(true.B) {
    when(addr === CSRList("sstatus")) { // * 0x100
      val sstatus = WireInit(0.U.asTypeOf(new Mstatus))
      sstatus.SD := mstatus.SD      
      sstatus.MXR := mstatus.MXR
      sstatus.SUM := mstatus.SUM
      sstatus.XS := mstatus.XS
      sstatus.FS := mstatus.FS
      sstatus.SPP := mstatus.SPP
      sstatus.SPIE := mstatus.SPIE
      sstatus.SIE := mstatus.SIE
      rdata := sstatus.asUInt
    }
    when(addr === CSRList("sie")) { // * 0x104
      rdata := mie.asUInt
    }
    when(addr === CSRList("stvec")) { // * 0x105
      rdata := stvec
    }
    when(addr === CSRList("scounteren")) { // * 0x106
      rdata := 0.U
    }
    when(addr === CSRList("sscratch")) { // * 0x140
      rdata := sscratch
    }
    when(addr === CSRList("sepc")) { // * 0x141
      rdata := sepc
    }
    when(addr === CSRList("scause")) { // * 0x142
      rdata := scause
    }
    when(addr === CSRList("stval")) { // * 0x143
      rdata := stval
    }
    when(addr === CSRList("sip")) { // * 0x144
      rdata := mip.asUInt
    }
    when(addr === CSRList("satp")) { // * 0x180
      rdata := satp
    }
    when(addr === CSRList("mstatus")) { // * 0x300
      rdata := mstatus.asUInt
    }
    when(addr === CSRList("misa")) { // * 0x301
      rdata := misa
    }
    when(addr === CSRList("medeleg")) { // * 0x302
      rdata := medeleg
    }
    when(addr === CSRList("mideleg")) { // * 0x303
      rdata := mideleg
    }
    when(addr === CSRList("mie")) { // * 0x304
      rdata := mie.asUInt
    }
    when(addr === CSRList("mtvec")) { // * 0x305
      rdata := mtvec
    }
    when(addr === CSRList("mcounteren")) { // * 0x306
      rdata := 0.U
    }
    when(addr === CSRList("menvcfg")) { // * 0x30A
      rdata := menvcfg.asUInt
    }
    when(addr === CSRList("mstatush")) { // * 0x310
      rdata := 0.U
    }
    when(addr === CSRList("menvcfgh")) { // * 0x31A
      rdata := 0.U
    }
    when(addr === CSRList("mscratch")) { // * 0x340
      rdata := mscratch
    }
    when(addr === CSRList("mepc")) { // * 0x341
      rdata := mepc
    }
    when(addr === CSRList("mcause")) { // * 0x342
      rdata := mcause
    }
    when(addr === CSRList("mtval")) { // * 0x343
      rdata := mtval
    }
    when(addr === CSRList("mip")) { // * 0x344
      rdata := mip.asUInt
    }
    
    when(addr === CSRList("time")) { // * 0xC01
      rdata := io.IN_mtime(31, 0)
    }
    when(addr === CSRList("timeh")) { // * 0xC81
      rdata := io.IN_mtime(63, 32)
    }

    when(addr === CSRList("mvendorid")) { // * 0xF11
      rdata := 0x79737978.U
    }
    when(addr === CSRList("marchid")) { // * 0xF12
      rdata := 23060238.U
    }
    when(addr === CSRList("mhartid")) { // * 0xF14
      rdata := 0.U
    }
  }

  when(doWrite) {
    when(addr === CSRList("sstatus")) { // * 0x100
      // printf("opcode %d sstatus: %x\n", inUop.opcode, wdata)
      // printf("rdata: %x\n", rdata)
      // printf("mstatus: %x\n", mstatus.asUInt)
      val status = WireInit(wdata.asTypeOf(new Mstatus))
      mstatus.MXR := status.MXR
      mstatus.SUM := status.SUM
      mstatus.FS := status.FS
      mstatus.SPP := status.SPP
      mstatus.SPIE := status.SPIE
      mstatus.SIE := status.SIE
      // * only FS can be non-zero
      mstatus.SD  := status.FS.orR
    }
    when(addr === CSRList("sie")) { // * 0x104
      mie := wdata.asTypeOf(new Mipe)
    }
    when(addr === CSRList("stvec")) { // * 0x105
      stvec := wdata
    }
    when(addr === CSRList("scounteren")) { // * 0x106
      // * do nothing
    }
    when(addr === CSRList("sscratch")) { // * 0x140
      sscratch := wdata
    }
    when(addr === CSRList("sepc")) { // * 0x141
      sepc := wdata
    }
    when(addr === CSRList("scause")) { // * 0x142
      scause := wdata
    }
    when(addr === CSRList("stval")) { // * 0x143
      stval := wdata
    }
    when(addr === CSRList("sip")) { // * 0x144
      mip := wdata.asTypeOf(new Mipe)
    }
    when(addr === CSRList("satp")) { // * 0x180
      satp := wdata
    }
    when(addr === CSRList("mstatus")) { // * 0x300
      // printf("write mstatus: %x\n", wdata)
      val status = WireInit(wdata.asTypeOf(new Mstatus))      
      mstatus.TSR := status.TSR
      mstatus.TW := status.TW
      mstatus.TVM := status.TVM
      mstatus.MXR := status.MXR
      mstatus.SUM := status.SUM
      mstatus.MPRV := status.MPRV
      mstatus.FS := status.FS
      mstatus.MPP := status.MPP
      mstatus.SPP := status.SPP      
      mstatus.MPIE := status.MPIE
      mstatus.SPIE := status.SPIE
      mstatus.MIE := status.MIE
      mstatus.SIE := status.SIE
      // * only FS can be non-zero
      mstatus.SD := status.FS.orR
    }
    when(addr === CSRList("misa")) { // * 0x301
      // * do nothing
    }
    when(addr === CSRList("medeleg")) { // * 0x302
      medeleg := wdata & 0xb7ff.U
    }
    when(addr === CSRList("mideleg")) { // * 0x303
      mideleg := wdata & 0x222.U
    }
    when(addr === CSRList("mie")) { // * 0x304
      mie := wdata.asTypeOf(new Mipe)
    }
    when(addr === CSRList("mtvec")) { // * 0x305
      mtvec := wdata
    }
    when(addr === CSRList("mcounteren")) { // * 0x306
      // * do nothing
    }
    when(addr === CSRList("menvcfg")) { // * 0x30A
      val envcfg = WireInit(wdata.asTypeOf(new Menvcfg))
      menvcfg.FIOM := envcfg.FIOM
    }
    when(addr === CSRList("mscratch")) { // * 0x340
      // printf("write mscratch: %x write: %x\n", mscratch, wdata)
      mscratch := wdata
    }
    when(addr === CSRList("mepc")) { // * 0x341
      mepc := wdata
    }
    when(addr === CSRList("mcause")) { // * 0x342
      mcause := wdata
    }
    when(addr === CSRList("mtval")) { // * 0x343
      mtval := wdata
    }
    when(addr === CSRList("mip")) { // * 0x344
      mip := wdata.asTypeOf(new Mipe)
    }    
  }

  mip.MTI := io.IN_MTIP

  // * VM control
  io.OUT_VMCSR.mode := satp(31)
  io.OUT_VMCSR.rootPPN := satp(19, 0)
  io.OUT_VMCSR.epm := Mux(mstatus.MPRV.asBool, mstatus.MPP, priv)
  io.OUT_VMCSR.priv := priv
  io.OUT_VMCSR.mxr := mstatus.MXR
  io.OUT_VMCSR.sum := mstatus.SUM

  // * Trap control
  val hasInterrupt = WireInit(false.B)
  val interruptCause = WireInit(0.U(4.W))
  val interruptDeleg = WireInit(false.B)

  // ** Interrupt pending?
  val MTI = mip.MTI && mie.MTI
  val STI = mip.STI && mie.STI
  val MSI = mip.MSI && mie.MSI
  val SSI = mip.SSI && mie.SSI
  
  when(priv < Priv.S || (priv === Priv.S && mstatus.SIE)) {
    when(SSI && mideleg(1)) {
      interruptCause := 1.U
      hasInterrupt := true.B      
      interruptDeleg := true.B
    }.elsewhen(STI && mideleg(5)) {
      interruptCause := 5.U
      hasInterrupt := true.B
      interruptDeleg := true.B
    }
  }
  // * Override S-mode interrupt with M-mode interrupt
  when(priv < Priv.M || mstatus.MIE) {
    when(MSI && !mideleg(3)) {
      interruptCause := 3.U
      hasInterrupt := true.B
      interruptDeleg := false.B
    }.elsewhen(MTI && !mideleg(7)) {
      interruptCause := 7.U
      hasInterrupt := true.B
      interruptDeleg := false.B
    }.elsewhen(SSI && !mideleg(1)) {
      interruptCause := 1.U
      hasInterrupt := true.B
      interruptDeleg := false.B
    }.elsewhen(STI && !mideleg(5)) {
      interruptCause := 5.U
      hasInterrupt := true.B
      interruptDeleg := false.B
    }
  }

  io.OUT_trapCSR.mtvec := mtvec
  io.OUT_trapCSR.mepc := mepc
  io.OUT_trapCSR.stvec := stvec
  io.OUT_trapCSR.sepc := sepc
  io.OUT_trapCSR.mideleg := mideleg
  io.OUT_trapCSR.medeleg := medeleg
  io.OUT_trapCSR.priv := priv
  io.OUT_trapCSR.interrupt := hasInterrupt
  io.OUT_trapCSR.interruptCause := interruptCause
  io.OUT_trapCSR.interruptDeleg := interruptDeleg

  // val error = Module(new Error)
  // error.io.ebreak := trapValid && trapCause === Exception.BREAKPOINT
  // error.io.access_fault := false.B
  // error.io.invalid_inst := false.B

  uopValid := inValid
  uop.dest := Dest.ROB
  uop.data := rdata
  uop.target := inUop.pc + 4.U
  uop.flag := Mux(illegal, FlagOp.ILLEGAL_INST, FlagOp.MISPREDICT_JUMP)
  uop.prd  := inUop.prd
  uop.robPtr := inUop.robPtr
  
  io.IN_readRegUop.ready := true.B

  io.OUT_writebackUop.valid := uopValid
  io.OUT_writebackUop.bits := uop
}
