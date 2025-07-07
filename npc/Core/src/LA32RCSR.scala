import chisel3._
import chisel3.util._
import utils._
import Priv.U

object LA32RCSRList {
  val csrMap: Map[String, UInt] = Map(
    // Basic CSRs
    "CRMD"      -> 0x0.U,    // 当前模式信息
    "PRMD"      -> 0x1.U,    // 例外前模式信息
    "EUEN"      -> 0x2.U,    // 扩展部件使能
    "ECFG"      -> 0x4.U,    // 例外配置
    "ESTAT"     -> 0x5.U,    // 例外状态
    "ERA"       -> 0x6.U,    // 例外返回地址
    "BADV"      -> 0x7.U,    // 出错虚地址
    "EENTRY"    -> 0xC.U,    // 例外入口地址
    
    // TLB related CSRs
    "TLBIDX"    -> 0x10.U,   // TLB 索引
    "TLBEHI"    -> 0x11.U,   // TLB 表项高位
    "TLBELO0"   -> 0x12.U,   // TLB 表项低位 0
    "TLBELO1"   -> 0x13.U,   // TLB 表项低位 1
    "ASID"      -> 0x18.U,   // 地址空间标识符
    "PGDL"      -> 0x19.U,   // 低半地址空间全局目录基址
    "PGDH"      -> 0x1A.U,   // 高半地址空间全局目录基址
    "PGD"       -> 0x1B.U,   // 全局目录基址
    
    // Processor identification
    "CPUID"     -> 0x20.U,   // 处理器编号
    
    // Data save registers
    "SAVE0"     -> 0x30.U,   // 数据保存0
    "SAVE1"     -> 0x31.U,   // 数据保存1
    "SAVE2"     -> 0x32.U,   // 数据保存2
    "SAVE3"     -> 0x33.U,   // 数据保存3
    
    // Timer related CSRs
    "TID"       -> 0x40.U,   // 定时器编号
    "TCFG"      -> 0x41.U,   // 定时器配置
    "TVAL"      -> 0x42.U,   // 定时器值
    "TICLR"     -> 0x44.U,   // 定时中断清除
    
    // LLBit control
    "LLBCTL"    -> 0x60.U,   // LLBit 控制
    
    // TLB refill exception entry
    "TLBRENTRY" -> 0x88.U,   // TLB 重填例外入口地址
    
    // Cache tag
    "CTAG"      -> 0x98.U,   // 高速缓存标签
    
    // Direct mapping windows
    "DMW0"      -> 0x180.U,  // 直接映射配置窗口0
    "DMW1"      -> 0x181.U   // 直接映射配置窗口1
  )

  def apply(name: String): UInt = csrMap(name)
  def exists(value: UInt): Bool = csrMap.map(_._2 === value).reduce(_ || _)
}


class CRMD extends CoreBundle {
  val _R0   = UInt(23.W)
  val DATM  = UInt(2.W)
  val DATF  = UInt(2.W)
  val PG    = UInt(1.W)
  val DA    = UInt(1.W)
  val IE    = UInt(1.W)
  val PLV   = UInt(2.W)
}

class PRMD extends CoreBundle {
  val _R0   = UInt(29.W)
  val PIE   = UInt(1.W)
  val PPLV  = UInt(2.W)
}

class EUEN extends CoreBundle {
  val _R0   = UInt(30.W)
  val FPE   = UInt(1.W)
}

class ECFG extends CoreBundle {
  val _R0   = UInt(19.W)
  val LIE   = UInt(13.W) // [10] is R0
}

class ESTAT extends CoreBundle {
  val _R0_2     = UInt(1.W)
  val EsubCode  = UInt(9.W)
  val Ecode     = UInt(6.W)
  val _R0_1     = UInt(3.W)
  val IPI       = UInt(1.W)
  val TI        = UInt(1.W)
  val _R0_0     = UInt(1.W)
  val IS_HW     = UInt(8.W) // R
  val IS_SW     = UInt(2.W) // RW
}

// * ERA is just UInt(32.W)

// * BADV is just UInt(32.W)

class EENTRY extends CoreBundle {
  val VA = UInt(26.W)
  val R0 = UInt(6.W)
}

// * CPUID is hardwired to 0x0

// * SAVE0~3 is just UInt(32.W)

class LLBCTL extends CoreBundle {
  val _R0    = UInt(29.W)
  val KLO    = UInt(1.W) // RW
  val WCLLB  = UInt(1.W) // W1
  val ROLLB  = UInt(1.W) // R
}

class TLBIDX extends CoreBundle {
  val NE     = UInt(1.W)
  val _R0_1  = UInt(1.W)
  val PS     = UInt(6.W)
  val _R0_0  = UInt(19.W)
  val TLBIDX = UInt(5.W)
}

class TLBEHI extends CoreBundle {
  val VPPN  = UInt(19.W)
  val _R0   = UInt(13.W)
}

class TLBELO extends CoreBundle {
  val PPN   = UInt(24.W)
  val _R0   = UInt(1.W)
  val G     = UInt(1.W)
  val MAT   = UInt(2.W)  
  val PLV   = UInt(2.W)
  val D     = UInt(1.W)
  val V     = UInt(1.W)
}

class ASID extends CoreBundle {
  val _R0_1    = UInt(8.W)
  val ASIDBITS = UInt(8.W) // Hardwired to 10
  val _R0_0    = UInt(6.W)
  val ASID     = UInt(10.W)
}

class PGD extends CoreBundle { // PGDL/H
  val Base = UInt(20.W)
  val _R0  = UInt(12.W)
}

class TLBRENTRY extends CoreBundle {
  val PA = UInt(26.W)
  val R0 = UInt(6.W)
}

class DMW extends CoreBundle {
  val VSEG  = UInt(3.W)
  val _R0_2 = UInt(1.W)
  val PSEG  = UInt(3.W)
  val _R0_1 = UInt(19.W)
  val MAT   = UInt(2.W)
  val PLV3  = UInt(1.W)
  val _R0_0 = UInt(2.W)
  val PLV0  = UInt(1.W)

  def matchVAddr(vaddr: UInt, priv: UInt): Bool = {
    ((priv === 0.U && PLV0.asBool) || (priv === 3.U && PLV3.asBool)) && vaddr(31, 29) === VSEG
  }
}

// * TID is just UInt(32.W)

class TCFG extends CoreBundle {
  val InitVal  = UInt(30.W)
  val Periodic = UInt(1.W)
  val En       = UInt(1.W)
}

// * TVAL is Read Only

class TICLR extends CoreBundle {
  val _R0 = UInt(31.W)
  val CLR = UInt(1.W) // W1
}

class CPUCFG0x1 extends CoreBundle {
  val VALEN = UInt(8.W)
  val PALEN = UInt(8.W)
  val _R0_0 = UInt(1.W)
  val PGMMU = UInt(1.W)
  val ARCH  = UInt(2.W)
}

class CPUCFG0x2 extends CoreBundle {
  val FP_DP = UInt(1.W)
  val FP_SP = UInt(1.W)
  val FP    = UInt(1.W)
}

class CPUCFG0x10 extends CoreBundle {
  val L2U_Inclusive = UInt(1.W)
  val _R0_1         = UInt(1.W)
  val L2U_Present   = UInt(2.W)
  val L1D_Present   = UInt(1.W)
  val _R0_0         = UInt(1.W)
  val L1I_Present   = UInt(1.W)
}

class CPUCFG0x11 extends CoreBundle {
  val Linesize = UInt(7.W)
  val Index    = UInt(8.W)
  val Way      = UInt(16.W)
}

class TLBCSR extends CoreBundle {
  val tlbidx = new TLBIDX
  val tlbehi = new TLBEHI
  val tlbelo = Vec(2, new TLBELO)
  val estat  = new ESTAT
}

class LA32RCSRIO extends CoreBundle {
  val IN_readRegUop  = Flipped(Decoupled(new ReadRegUop))
  val OUT_InvTLBOp = Valid(new InvTLBOp)
  val OUT_writebackUop  = Valid(new WritebackUop)
  val IN_CSRCtrl = Flipped(new CSRCtrl)
  val OUT_VMCSR = new VMCSR
  val OUT_TLBCSR = new TLBCSR
  val OUT_trapCSR = new TrapCSR
  val IN_mtime = Input(UInt(64.W))
  val IN_MTIP = Flipped(Bool())
  val IN_xtvalRec = Flipped(Valid(new XtvalRec))
}

class LA32RCSR extends CoreModule {
  val io = IO(new LA32RCSRIO)

  // * CSRs
  val crmd = RegInit(8.U.asTypeOf(new CRMD))
  val crmdU = WireInit(crmd.asUInt)
  dontTouch(crmdU)

  val prmd = RegInit(0.U.asTypeOf(new PRMD))
  val prmdU = WireInit(prmd.asUInt)
  dontTouch(prmdU)

  val euen = RegInit(0.U.asTypeOf(new EUEN))
  val euenU = WireInit(euen.asUInt)
  dontTouch(euenU)

  val ecfg = RegInit(0.U.asTypeOf(new ECFG))
  val ecfgU = WireInit(ecfg.asUInt)
  dontTouch(ecfgU)

  val estat = RegInit(0.U.asTypeOf(new ESTAT))
  val estatU = WireInit(estat.asUInt)
  dontTouch(estatU)

  val era = Reg(UInt(32.W))

  val badv = Reg(UInt(32.W))

  val eentry = RegInit(0.U.asTypeOf(new EENTRY))
  val eentryU = WireInit(eentry.asUInt)
  dontTouch(eentryU)

  val tlbidx = RegInit(0.U.asTypeOf(new TLBIDX))
  val tlbidxU = WireInit(tlbidx.asUInt)
  dontTouch(tlbidxU)

  val tlbehi = RegInit(0.U.asTypeOf(new TLBEHI))
  val tlbehiU = WireInit(tlbehi.asUInt)
  dontTouch(tlbehiU)

  val tlbelo0 = RegInit(0.U.asTypeOf(new TLBELO))
  val tlbelo0U = WireInit(tlbelo0.asUInt)
  dontTouch(tlbelo0U)

  val tlbelo1 = RegInit(0.U.asTypeOf(new TLBELO))
  val tlbelo1U = WireInit(tlbelo1.asUInt)
  dontTouch(tlbelo1U)

  val asid = RegInit(0.U.asTypeOf(new ASID))
  val asidU = WireInit(asid.asUInt)
  dontTouch(asidU)

  val pgdl = RegInit(0.U.asTypeOf(new PGD))
  val pgdlU = WireInit(pgdl.asUInt)
  dontTouch(pgdlU)

  val pgdh = RegInit(0.U.asTypeOf(new PGD))
  val pgdhU = WireInit(pgdh.asUInt)
  dontTouch(pgdhU)

  val pgd = WireInit(Mux(badv(31), pgdh.asUInt, pgdl.asUInt))
  dontTouch(pgd)

  val tlbrentry = Reg(new TLBRENTRY)
  val tlbrentryU = WireInit(tlbrentry.asUInt)
  dontTouch(tlbrentryU)

  val dmw0 = RegInit(0.U.asTypeOf(new DMW))
  val dmw0U = WireInit(dmw0.asUInt)
  dontTouch(dmw0U)

  val dmw1 = RegInit(0.U.asTypeOf(new DMW))
  val dmw1U = WireInit(dmw1.asUInt)
  dontTouch(dmw1U)

  val tid = RegInit(0.U(32.W))

  val tcfg = RegInit(0.U.asTypeOf(new TCFG))
  val tcfgU = WireInit(tcfg.asUInt)
  dontTouch(tcfgU)

  val tval = RegInit(0.U(32.W))

  val ticlr = RegInit(0.U.asTypeOf(new TICLR))
  val ticlrU = WireInit(ticlr.asUInt)
  dontTouch(ticlrU)

  val llbctl = RegInit(0.U.asTypeOf(new LLBCTL))
  val llbctlU = WireInit(llbctl.asUInt)
  dontTouch(llbctlU)
  val save0 = Reg(UInt(32.W))
  val save1 = Reg(UInt(32.W))
  val save2 = Reg(UInt(32.W))
  val save3 = Reg(UInt(32.W))

  val inUopValid = io.IN_readRegUop.valid
  val inUop = io.IN_readRegUop.bits
  val cpucfgIndex = inUop.src1
  val addr = inUop.imm(13, 0)
  val isInvTLB = inUop.opcode === CSROp.INVTLB
  val isCpucfg = inUop.opcode === CSROp.CPUCFG
  val isRdcntID = inUop.opcode === CSROp.RDCNT_ID_W
  val isRdcntVL = inUop.opcode === CSROp.RDCNT_VL_W
  val isRdcntVH = inUop.opcode === CSROp.RDCNT_VH_W
  val isCsrXchg = inUop.opcode === CSROp.CSRXCHG
  val csrRen = inUopValid
  val csrWen = inUopValid && (inUop.opcode === CSROp.CSRWR || inUop.opcode === CSROp.CSRXCHG)
  val doWrite = csrWen

  val rdata = Wire(UInt(32.W))
  val wdata = Wire(UInt(32.W))
  val data = WireInit(inUop.src1)
  val wmask = Wire(UInt(32.W))
  wmask := Mux(isCsrXchg, inUop.src2, Fill(32, 1.U))

  val uop = Reg(new WritebackUop)
  val uopValid = RegInit(false.B)

  // wdata for every bit, Mux(wmask[i], data, rdata)
  wdata := (data & wmask) | (rdata & ~wmask)
  rdata := 0.U

  val timer64 = RegInit(0.U(64.W))
  timer64 := timer64 + 1.U
 
  when(tcfg.En.asBool) {
    when(tval === 0.U) {
      when(tcfg.Periodic.asBool) {
        tval := Cat(tcfg.InitVal, 1.U(2.W)) // ! see below TCFG
      }
    }.otherwise {
      tval := tval - 1.U
    }
  }
  when(isCpucfg) {
    when(cpucfgIndex === 0x1.U) {
      val cpucfg0x1 = WireInit(0.U.asTypeOf(new CPUCFG0x1))
      cpucfg0x1.VALEN := 31.U
      cpucfg0x1.PALEN := 31.U
      cpucfg0x1.PGMMU := 1.U
      cpucfg0x1.ARCH := 0.U
      rdata := cpucfg0x1.asUInt
    }.elsewhen(cpucfgIndex === 0x2.U) {
      val cpucfg0x2 = WireInit(0.U.asTypeOf(new CPUCFG0x2))
      cpucfg0x2.FP_DP := 0.U
      cpucfg0x2.FP_SP := 0.U
      cpucfg0x2.FP := 0.U
      rdata := cpucfg0x2.asUInt
    }.elsewhen(cpucfgIndex === 0x10.U) {
      val cpucfg0x10 = WireInit(0.U.asTypeOf(new CPUCFG0x10))
      cpucfg0x10.L2U_Inclusive := 0.U
      cpucfg0x10.L2U_Present := 0.U
      cpucfg0x10.L1D_Present := 1.U
      cpucfg0x10.L1I_Present := 1.U
      rdata := cpucfg0x10.asUInt
    }.elsewhen(cpucfgIndex === 0x11.U) {
      val cpucfg0x11 = WireInit(0.U.asTypeOf(new CPUCFG0x11))
      cpucfg0x11.Linesize := 5.U
      cpucfg0x11.Index := 7.U
      cpucfg0x11.Way := 1.U
      rdata := cpucfg0x11.asUInt
    }.elsewhen(cpucfgIndex === 0x12.U) {
      val cpucfg0x12 = WireInit(0.U.asTypeOf(new CPUCFG0x11))
      cpucfg0x12.Linesize := 5.U
      cpucfg0x12.Index := 7.U
      cpucfg0x12.Way := 1.U
      rdata := cpucfg0x12.asUInt
    }
  }.elsewhen(isRdcntID) {
    rdata := tid
  }.elsewhen(isRdcntVL) {
    rdata := timer64(31, 0)
  }.elsewhen(isRdcntVH) {
    rdata := timer64(63, 32)
  }.otherwise {
    when(addr === LA32RCSRList("CRMD")){ // * 0x0
      rdata := crmd.asUInt
      when(doWrite) {
        val crmdNext = WireInit(wdata.asTypeOf(new CRMD))
        crmd.PLV  := crmdNext.PLV
        crmd.IE   := crmdNext.IE
        crmd.DA   := crmdNext.DA
        crmd.PG   := crmdNext.PG
        crmd.DATF := crmdNext.DATF
        crmd.DATM := crmdNext.DATM
      }
    }
    when(addr === LA32RCSRList("PRMD")) { // * 0x1
      rdata := prmd.asUInt
      // Write
      when(doWrite) {
        val prmdNext = WireInit(wdata.asTypeOf(new PRMD))
        prmd.PPLV := prmdNext.PPLV
        prmd.PIE  := prmdNext.PIE
      }
    }
    when(addr === LA32RCSRList("EUEN")) { // * 0x2
      rdata := euen.asUInt
      // Write
      when(doWrite) {
        val euenNext = WireInit(wdata.asTypeOf(new EUEN))
        euen.FPE := euenNext.FPE
      }
    }
    when(addr === LA32RCSRList("ECFG")) { // * 0x4
      rdata := ecfg.asUInt
      // Write
      when(doWrite) {
        val ecfgNext = WireInit(wdata.asTypeOf(new ECFG))
        ecfg.LIE := Cat(ecfgNext.LIE(12, 11), 0.U(1.W), ecfgNext.LIE(9, 0)) // LIE[10] is R0
      }
    }
    when(addr === LA32RCSRList("ESTAT")) { // * 0x5
      rdata := estat.asUInt
      // Write
      when(doWrite) {
        val estatNext = WireInit(wdata.asTypeOf(new ESTAT))
        estat.IS_SW := estatNext.IS_SW
      }
    }
    when(addr === LA32RCSRList("ERA")) { // * 0x6
      rdata := era
      // Write
      when(doWrite) {
        era := wdata
      }
    }
    when(addr === LA32RCSRList("BADV")) { // * 0x7
      rdata := badv
      // Write
      when(doWrite) {
        badv := wdata
      }
    }
    when(addr === LA32RCSRList("EENTRY")) { // * 0xC
      rdata := eentry.asUInt
      // Write
      when(doWrite) {
        val eentryNext = WireInit(wdata.asTypeOf(new EENTRY))
        eentry.VA := eentryNext.VA
      }
    }
    when(addr === LA32RCSRList("TLBIDX")) { // * 0x10
      rdata := tlbidx.asUInt
      // Write
      when(doWrite) {
        val tlbidxNext = WireInit(wdata.asTypeOf(new TLBIDX))
        tlbidx.TLBIDX := tlbidxNext.TLBIDX
        tlbidx.PS := tlbidxNext.PS
        tlbidx.NE := tlbidxNext.NE
      }
    }
    when(addr === LA32RCSRList("TLBEHI")) { // * 0x11
      rdata := tlbehi.asUInt
      // Write
      when(doWrite) {
        val tlbehiNext = WireInit(wdata.asTypeOf(new TLBEHI))
        tlbehi.VPPN := tlbehiNext.VPPN
      }
    }
    when(addr === LA32RCSRList("TLBELO0")) { // * 0x12
      rdata := tlbelo0.asUInt
      // Write
      when(doWrite) {
        val tlbelo0Next = WireInit(wdata.asTypeOf(new TLBELO))
        tlbelo0.PPN := tlbelo0Next.PPN
        tlbelo0.G := tlbelo0Next.G
        tlbelo0.MAT := tlbelo0Next.MAT
        tlbelo0.PLV := tlbelo0Next.PLV
        tlbelo0.D := tlbelo0Next.D
        tlbelo0.V := tlbelo0Next.V
      }
    }
    when(addr === LA32RCSRList("TLBELO1")) { // * 0x13
      rdata := tlbelo1.asUInt
      // Write
      when(doWrite) {
        val tlbelo1Next = WireInit(wdata.asTypeOf(new TLBELO))
        tlbelo1.PPN := tlbelo1Next.PPN
        tlbelo1.G := tlbelo1Next.G
        tlbelo1.MAT := tlbelo1Next.MAT
        tlbelo1.PLV := tlbelo1Next.PLV  
        tlbelo1.D := tlbelo1Next.D
        tlbelo1.V := tlbelo1Next.V
      }
    }
    when(addr === LA32RCSRList("ASID")) { // * 0x18
      rdata := asid.asUInt
      // Write
      when(doWrite) {
        val asidNext = WireInit(wdata.asTypeOf(new ASID))
        asid.ASID := asidNext.ASID
        asid.ASIDBITS := asidNext.ASIDBITS
      }
    }
    when(addr === LA32RCSRList("PGDL")) { // * 0x19
      rdata := pgdl.asUInt
      // Write
      when(doWrite) {
        val pgdlNext = WireInit(wdata.asTypeOf(new PGD))
        pgdl.Base := pgdlNext.Base
      }
    }
    when(addr === LA32RCSRList("PGDH")) { // * 0x1A
      rdata := pgdh.asUInt
      // Write
      when(doWrite) {
        val pgdhNext = WireInit(wdata.asTypeOf(new PGD))
        pgdh.Base := pgdhNext.Base
      }
    }
    when(addr === LA32RCSRList("PGD")) { // * 0x1B
      rdata := pgd
    }
    when(addr === LA32RCSRList("CPUID")) { // * 0x20
      rdata := 0.U
    }
    when(addr === LA32RCSRList("SAVE0")) { // * 0x30
      rdata := save0
      // Write
      when(doWrite) {
        save0 := wdata
      }
    }
    when(addr === LA32RCSRList("SAVE1")) { // * 0x31
      rdata := save1
      // Write
      when(doWrite) {
        save1 := wdata
      }
    }
    when(addr === LA32RCSRList("SAVE2")) { // * 0x32
      rdata := save2
      // Write
      when(doWrite) {
        save2 := wdata
      }
    }
    when(addr === LA32RCSRList("SAVE3")) { // * 0x33
      rdata := save3
      // Write
      when(doWrite) {
        save3 := wdata
      }
    }
    when(addr === LA32RCSRList("TID")) { // * 0x40
      rdata := tid
      // Write
      when(doWrite) {
        tid := wdata
      }
    }
    when(addr === LA32RCSRList("TCFG")) { // * 0x41
      rdata := tcfg.asUInt
      // Write
      when(doWrite) {
        val tcfgNext = WireInit(wdata.asTypeOf(new TCFG))
        tcfg.InitVal := tcfgNext.InitVal
        tcfg.Periodic := tcfgNext.Periodic
        tcfg.En := tcfgNext.En
        tval := Cat(tcfgNext.InitVal, 1.U(2.W)) // ! 1 since we need to detect the edge of tval 1->0
      }
    }
    when(addr === LA32RCSRList("TVAL")) { // * 0x42
      rdata := tval
    }
    when(addr === LA32RCSRList("TICLR")) { // * 0x44
      rdata := ticlr.asUInt
      // Write
      when(doWrite) {
        val ticlrNext = WireInit(wdata.asTypeOf(new TICLR))
        when(ticlrNext.CLR === 1.U) {
          estat.TI := 0.U
        }
      }
    }
    when(addr === LA32RCSRList("LLBCTL")) { // * 0x60
      rdata := llbctl.asUInt
      // Write
      when(doWrite) {
        val llbctlNext = WireInit(wdata.asTypeOf(new LLBCTL))
        llbctl.KLO := llbctlNext.KLO
        llbctl.WCLLB := llbctlNext.WCLLB
        llbctl.ROLLB := llbctlNext.ROLLB
      }
    }
    when(addr === LA32RCSRList("TLBRENTRY")) { // * 0x88
      rdata := tlbrentry.asUInt
      // Write
      when(doWrite) {
        val tlbrentryNext = WireInit(wdata.asTypeOf(new TLBRENTRY))
        tlbrentry.PA := tlbrentryNext.PA
      }
    }.elsewhen(addr === LA32RCSRList("DMW0")) { // * 0x180
      rdata := dmw0.asUInt
      // Write
      when(doWrite) {
        val dmw0Next = WireInit(wdata.asTypeOf(new DMW))
        dmw0.VSEG := dmw0Next.VSEG
        dmw0.PSEG := dmw0Next.PSEG
        dmw0.MAT := dmw0Next.MAT
        dmw0.PLV3 := dmw0Next.PLV3
        dmw0.PLV0 := dmw0Next.PLV0
      }
    }
    when(addr === LA32RCSRList("DMW1")) { // * 0x181
      rdata := dmw1.asUInt
      // Write
      when(doWrite) {
        val dmw1Next = WireInit(wdata.asTypeOf(new DMW))
        dmw1.VSEG := dmw1Next.VSEG
        dmw1.PSEG := dmw1Next.PSEG
        dmw1.MAT := dmw1Next.MAT
        dmw1.PLV3 := dmw1Next.PLV3
        dmw1.PLV0 := dmw1Next.PLV0
      }
    }
  }

  // * TVAL edge detect
  val tvalLatch1 = RegNext(tval(0))
  when(tcfg.En.asBool && tvalLatch1 && tval === 0.U) {
    estat.TI := 1.U
  }

  when(io.IN_CSRCtrl.trap) {
    prmd.PPLV := crmd.PLV
    prmd.PIE := crmd.IE
    crmd.IE := 0.U
    crmd.PLV := 0.U
    when(io.IN_CSRCtrl.cause === Exception.TLBR) {
      crmd.DA := 1.U
      crmd.PG := 0.U
    }
    era := io.IN_CSRCtrl.pc
    estat.Ecode := io.IN_CSRCtrl.cause
    estat.EsubCode := 0.U
    // TODO badv/ tlbhi
    // Fetch
    when(io.IN_CSRCtrl.cause === Exception.ADEF || 
         io.IN_CSRCtrl.cause === Exception.PIF ||
         io.IN_CSRCtrl.fetch) {
      badv := io.IN_CSRCtrl.pc
      when(io.IN_CSRCtrl.cause =/= Exception.ADEF) {
        tlbehi.VPPN := io.IN_CSRCtrl.pc(31, 13)
      }
    }.elsewhen(io.IN_xtvalRec.valid) {
      badv := io.IN_xtvalRec.bits.tval
      when(io.IN_CSRCtrl.cause =/= Exception.ALE) {
        tlbehi.VPPN := io.IN_xtvalRec.bits.tval(31, 13)
      }
    }
    
  }

  when(io.IN_CSRCtrl.ertn) {
    crmd.PLV := prmd.PPLV
    crmd.IE := prmd.PIE
    when(estat.Ecode === Exception.TLBR) {
      crmd.DA := 0.U
      crmd.PG := 1.U
    }
    // TODO llbit
  }


  when(io.IN_CSRCtrl.tlbrd) {
    when(!io.IN_CSRCtrl.tlbEntry.E) {
      // * Invalid TLB entry
      tlbidx.NE := 1.U
      tlbidx.PS := 0.U
      asid.ASID := 0.U
      tlbehi := 0.U.asTypeOf(new TLBEHI)
      tlbelo0 := 0.U.asTypeOf(new TLBELO)
      tlbelo1 := 0.U.asTypeOf(new TLBELO)
    }.otherwise {
      // * Valid TLB entry
      tlbidx.NE := 0.U
      tlbidx.PS := io.IN_CSRCtrl.tlbEntry.PS
      asid.ASID := io.IN_CSRCtrl.tlbEntry.ASID
      tlbehi.VPPN := io.IN_CSRCtrl.tlbEntry.VPPN
      val G = io.IN_CSRCtrl.tlbEntry.G
      tlbelo0 := io.IN_CSRCtrl.tlbEntry.PPN(0).toTLBELO(G)
      tlbelo1 := io.IN_CSRCtrl.tlbEntry.PPN(1).toTLBELO(G)
    }
  }.elsewhen(io.IN_CSRCtrl.tlbsrch) {
    when(io.IN_CSRCtrl.hit) {
      // * Hit
      tlbidx.NE := 0.U
      tlbidx.TLBIDX := io.IN_CSRCtrl.tlbidx
    }.otherwise {
      tlbidx.NE := 1.U
    }
  }

  val hasInterrupt = crmd.IE.asBool && 
    (estatU(12, 0) & ecfg.LIE).orR

  uopValid := inUopValid
  uop.dest := Dest.ROB
  uop.data := rdata
  uop.target := inUop.pc + 4.U
  uop.flag := FlagOp.MISPREDICT_JUMP
  uop.prd := inUop.prd
  uop.robPtr := inUop.robPtr

  io.IN_readRegUop.ready := true.B

  // * Invalidate TLB
  io.OUT_InvTLBOp.valid := isInvTLB && inUopValid
  io.OUT_InvTLBOp.bits.op := io.IN_readRegUop.bits.imm(4, 0)
  io.OUT_InvTLBOp.bits.asid := io.IN_readRegUop.bits.src1(9, 0)
  io.OUT_InvTLBOp.bits.vppn := io.IN_readRegUop.bits.src2(31, 13)

  io.OUT_writebackUop.valid := uopValid
  io.OUT_writebackUop.bits := uop

  io.OUT_VMCSR.asid := asid.ASID
  io.OUT_VMCSR.pg := crmd.PG
  io.OUT_VMCSR.priv := crmd.PLV
  io.OUT_VMCSR.datm := crmd.DATM
  io.OUT_VMCSR.datf := crmd.DATF
  io.OUT_VMCSR.dmw0 := dmw0
  io.OUT_VMCSR.dmw1 := dmw1

  io.OUT_TLBCSR.tlbidx := tlbidx
  io.OUT_TLBCSR.tlbehi := tlbehi
  io.OUT_TLBCSR.tlbelo := VecInit(tlbelo0, tlbelo1)
  io.OUT_TLBCSR.estat := estat

  io.OUT_trapCSR.priv := crmd.PLV
  io.OUT_trapCSR.eentry := eentryU
  io.OUT_trapCSR.tlbrentry := tlbrentryU
  io.OUT_trapCSR.era := era
  io.OUT_trapCSR.interrupt := hasInterrupt
}  