import chisel3._
import chisel3.util._
import utils._
import Config.XLEN
import chisel3.SpecifiedDirection.Flip
import CSRList.{mip => mip}
import CSRList.{mscratch => mscratch}

object CSRList {
  val sstatus        = 0x100.U
  val sie            = 0x104.U
  val stvec          = 0x105.U
  val scounteren     = 0x106.U

  val sscratch       = 0x140.U
  val sepc           = 0x141.U
  val scause         = 0x142.U
  val stval          = 0x143.U
  val sip            = 0x144.U
  val satp           = 0x180.U

  val mstatus        = 0x300.U
  val misa           = 0x301.U
  val medeleg        = 0x302.U
  val mideleg        = 0x303.U
  val mie            = 0x304.U
  val mtvec          = 0x305.U
  val mcounteren     = 0x306.U
  val menvcfg        = 0x30A.U
  val mstatush       = 0x310.U
  val menvcfgh       = 0x31A.U
  val mscratch       = 0x340.U
  val mepc           = 0x341.U
  val mcause         = 0x342.U
  val mtval          = 0x343.U
  val mip            = 0x344.U

  val time           = 0xC01.U
  val timeh          = 0xC81.U

  val mvendorid      = 0xF11.U
  val marchid        = 0xF12.U
  val mipid          = 0xF13.U
  val mhartid        = 0xF14.U
}

object Priv {
  val M = 3.U
  val S = 1.U
  val U = 0.U
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
  val MIE     = UInt(1.W) /*  3:3 */
  val _WPRI_1 = UInt(1.W) /*  2:2 */
  val SIE     = UInt(1.W) /*  1:1 */
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
}

class CSRIO extends CoreBundle {
  val IN_readRegUop  = Flipped(Decoupled(new ReadRegUop))
  val OUT_writebackUop  = Valid(new WritebackUop)
  val IN_CSRCtrl = Flipped(new CSRCtrl)
  val OUT_VMCSR = new VMCSR
  val OUT_trapCSR = new TrapCSR
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
  val _ZERO_2 = UInt(24.W)
  val MTI = UInt(1.W)
  val _ZERO_1 = UInt(1.W)
  val STI = UInt(1.W)
  val _ZERO_0 = UInt(5.W)
}

class Menvcfg extends CoreBundle {
  val _ZERO_1 = UInt(31.W)
  val FIOM    = UInt(1.W)
}

class CSR extends Module {
  val io = IO(new CSRIO)

  val priv    = RegInit(3.U(2.W))  

/*
  object CSRList {
    val sstatus        = 0x100.U
    val sie            = 0x104.U
    val stvec          = 0x105.U
    val scounteren     = 0x106.U
  
    val sscratch       = 0x140.U
    val sepc           = 0x141.U
    val scause         = 0x142.U
    val stval          = 0x143.U
    val sip            = 0x144.U
    val satp           = 0x180.U
  
    val mstatus        = 0x300.U
    val misa           = 0x301.U
    val medeleg        = 0x302.U
    val mideleg        = 0x303.U
    val mie            = 0x304.U
    val mtvec          = 0x305.U
    val mcounteren     = 0x306.U
    val menvcfg        = 0x30A.U
    val mstatush       = 0x310.U
    val menvcfgh       = 0x31A.U
    val mscratch       = 0x340.U
    val mepc           = 0x341.U
    val mcause         = 0x342.U
    val mtval          = 0x343.U
    val mip            = 0x344.U
  
    val time           = 0xC01.U
    val timeh          = 0xC81.U
  
    val mvendorid      = 0xF11.U
    val marchid        = 0xF12.U
    val mipid          = 0xF13.U
    val mhartid        = 0xF14.U
  }
*/
  // * sstatus    0x100
  // * sie        0x104
  val stvec        = Reg(UInt(XLEN.W))                  // * 0x105
  // * scounteren 0x106
  val sscratch     = Reg(UInt(XLEN.W))                  // * 0x140
  val sepc         = Reg(UInt(XLEN.W))                  // * 0x141
  val scause       = Reg(UInt(XLEN.W))                  // * 0x142
  val stval        = Reg(UInt(XLEN.W))                  // * 0x143
  // * sip        0x144
  val satp         = RegInit(0.U(32.W))                 // * 0x180

  val mstatus      = RegInit(0.U.asTypeOf(new Mstatus)) // * 0x300
  // * misa       0x301
  val medeleg      = RegInit(0.U(XLEN.W))               // * 0x302
  val mideleg      = RegInit(0.U(XLEN.W))               // * 0x303
  val mie          = RegInit(0.U.asTypeOf(new Mipe))    // * 0x304
  val mtvec        = Reg(UInt(32.W))                    // * 0x305
  // * mcounteren 0x306
  val menvcfg      = RegInit(0.U.asTypeOf(new Menvcfg)) // * 0x30A
  // * mstatush   0x310
  // * menvcfgh   0x31A

  val mscratch     = Reg(UInt(XLEN.W))                  // * 0x340
  val mepc         = Reg(UInt(XLEN.W))                  // * 0x341
  val mcause       = Reg(UInt(XLEN.W))                  // * 0x342
  val mtval        = Reg(UInt(XLEN.W))                  // * 0x343
  val mip          = RegInit(0.U.asTypeOf(new Mipe))    // * 0x344

  // * time       0xC01
  // * timeh      0xC81

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
    wdata := rdata | inUop.imm(16, 12)
  }.elsewhen(inUop.opcode === CSROp.CSRRCI) {
    wdata := rdata & ~inUop.imm(16, 12)
  }
  
  val privError = addr(9, 8) > priv
  val roError   = wen && addr(11, 10) === 3.U
  val illegal = privError || roError
  val doRead = ren && !illegal
  val doWrite = wen && !illegal

  val trapValid = io.IN_CSRCtrl.trap
  val trapCause = io.IN_CSRCtrl.cause
  val trapPC    = io.IN_CSRCtrl.pc
  val mret      = io.IN_CSRCtrl.mret
  val sret      = io.IN_CSRCtrl.sret

  val uop = Reg(new WritebackUop)
  val uopValid = RegInit(false.B)

  when(trapValid) {
    when(io.IN_CSRCtrl.delegate) {
      scause := trapCause
      sepc := trapPC
      mstatus.SPIE := mstatus.SIE
      mstatus.SIE := 0.U
      mstatus.SPP := priv
      priv := Priv.S
    }.otherwise {
      mcause := trapCause
      mepc := trapPC
      mstatus.MPIE := mstatus.MIE
      mstatus.MIE := 0.U
      mstatus.MPP := priv
      priv := Priv.M    
    }
  }

  when(mret) {
    when(mstatus.MPP < Priv.M) {
      mstatus.MPRV := 0.U
    }
    mstatus.MIE := mstatus.MPIE
    mstatus.MPIE := 1.U
    mstatus.MPP := Priv.U
    priv := mstatus.MPP
  }

  when(sret) {
    when(mstatus.SPP < Priv.M) {
      mstatus.MPRV := 0.U
    }
    mstatus.SIE := mstatus.SPIE
    mstatus.SPIE := 1.U
    mstatus.SPP := Priv.U
    priv := mstatus.SPP
  }

  rdata := 0.U
  when(doRead) {
    when(addr === CSRList.sstatus) { // * 0x100
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
    when(addr === CSRList.sie) { // * 0x104
      rdata := mie.asUInt
    }
    when(addr === CSRList.stvec) { // * 0x105
      rdata := stvec
    }
    when(addr === CSRList.scounteren) { // * 0x106
      rdata := 0.U
    }
    when(addr === CSRList.sscratch) { // * 0x140
      rdata := sscratch
    }
    when(addr === CSRList.sepc) { // * 0x141
      rdata := sepc
    }
    when(addr === CSRList.scause) { // * 0x142
      rdata := scause
    }
    when(addr === CSRList.stval) { // * 0x143
      rdata := stval
    }
    when(addr === CSRList.sip) { // * 0x144
      rdata := mip.asUInt
    }
    when(addr === CSRList.satp) { // * 0x180
      rdata := satp
    }
    when(addr === CSRList.mstatus) { // * 0x300
      rdata := mstatus.asUInt
    }
    when(addr === CSRList.medeleg) { // * 0x302
      rdata := medeleg
    }
    when(addr === CSRList.mideleg) { // * 0x303
      rdata := mideleg
    }
    when(addr === CSRList.mie) { // * 0x304
      rdata := mie.asUInt
    }
    when(addr === CSRList.mtvec) { // * 0x305
      rdata := mtvec
    }
    when(addr === CSRList.mcounteren) { // * 0x306
      rdata := 0.U
    }
    when(addr === CSRList.menvcfg) { // * 0x30A
      rdata := menvcfg.asUInt
    }
    when(addr === CSRList.mstatush) { // * 0x310
      rdata := 0.U
    }
    when(addr === CSRList.menvcfgh) { // * 0x31A
      rdata := 0.U
    }
    when(addr === CSRList.mscratch) { // * 0x340
      rdata := mscratch
    }
    when(addr === CSRList.mepc) { // * 0x341
      rdata := mepc
    }
    when(addr === CSRList.mcause) { // * 0x342
      rdata := mcause
    }
    when(addr === CSRList.mip) { // * 0x344
      rdata := mip.asUInt
    }
    
    when(addr === CSRList.time) { // * 0xC01
      rdata := 0.U
    }
    when(addr === CSRList.timeh) { // * 0xC81
      rdata := 0.U
    }

    when(addr === CSRList.mvendorid) { // * 0xF11
      rdata := 0x79737978.U
    }
    when(addr === CSRList.marchid) { // * 0xF12
      rdata := 23060238.U
    }
    when(addr === CSRList.mhartid) { // * 0xF14
      rdata := 0.U
    }
  }

  when(doWrite) {
    when(addr === CSRList.sstatus) { // * 0x100
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
    when(addr === CSRList.sie) { // * 0x104
      mie := wdata.asTypeOf(new Mipe)
    }
    when(addr === CSRList.stvec) { // * 0x105
      stvec := wdata
    }
    when(addr === CSRList.scounteren) { // * 0x106
      // * do nothing
    }
    when(addr === CSRList.sscratch) { // * 0x140
      sscratch := wdata
    }
    when(addr === CSRList.sepc) { // * 0x141
      sepc := wdata
    }
    when(addr === CSRList.scause) { // * 0x142
      scause := wdata
    }
    when(addr === CSRList.stval) { // * 0x143
      stval := wdata
    }
    when(addr === CSRList.sip) { // * 0x144
      mip := wdata.asTypeOf(new Mipe)
    }
    when(addr === CSRList.satp) { // * 0x180
      satp := wdata
    }
    when(addr === CSRList.mstatus) { // * 0x300
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
    when(addr === CSRList.medeleg) { // * 0x302
      medeleg := wdata
    }
    when(addr === CSRList.mideleg) { // * 0x303
      mideleg := wdata
    }
    when(addr === CSRList.mie) { // * 0x304
      mie := wdata.asTypeOf(new Mipe)
    }
    when(addr === CSRList.mtvec) { // * 0x305
      mtvec := wdata
    }
    when(addr === CSRList.mcounteren) { // * 0x306
      // * do nothing
    }
    when(addr === CSRList.menvcfg) { // * 0x30A
      val envcfg = WireInit(wdata.asTypeOf(new Menvcfg))
      menvcfg.FIOM := envcfg.FIOM
    }
    when(addr === CSRList.mscratch) { // * 0x340
      printf("write mscratch: %x %x\n", inUop.src1, wdata)
      mscratch := wdata
    }
    when(addr === CSRList.mepc) { // * 0x341
      mepc := wdata
    }
    when(addr === CSRList.mcause) { // * 0x342
      mcause := rdata
    }
    when(addr === CSRList.mip) { // * 0x344
      mip := wdata.asTypeOf(new Mipe)
    }    
  }

  // * VM control
  io.OUT_VMCSR.mode := satp(31)
  io.OUT_VMCSR.rootPPN := satp(19, 0)
  io.OUT_VMCSR.epm := Mux(mstatus.MPRV.asBool, mstatus.MPP, priv)
  io.OUT_VMCSR.priv := priv
  io.OUT_VMCSR.mxr := mstatus.MXR
  io.OUT_VMCSR.sum := mstatus.SUM

  // * Trap control
  io.OUT_trapCSR.mtvec := mtvec
  io.OUT_trapCSR.mepc := mepc
  io.OUT_trapCSR.stvec := stvec
  io.OUT_trapCSR.sepc := sepc
  io.OUT_trapCSR.mideleg := mideleg
  io.OUT_trapCSR.medeleg := medeleg
  io.OUT_trapCSR.priv := priv

  val error = Module(new Error)
  error.io.ebreak := trapValid && trapCause === FlagOp.BREAKPOINT
  error.io.access_fault := false.B
  error.io.invalid_inst := false.B

  uopValid := inValid
  uop.dest := Dest.ROB
  uop.data := rdata
  uop.target := inUop.pc + 4.U
  uop.flag := FlagOp.MISPREDICT
  uop.prd  := inUop.prd
  uop.robPtr := inUop.robPtr
  
  io.IN_readRegUop.ready := true.B

  io.OUT_writebackUop.valid := uopValid
  io.OUT_writebackUop.bits := uop
}
