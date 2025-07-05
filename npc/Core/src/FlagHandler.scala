import chisel3._
import chisel3.util._
import utils._

class FlagHandlerIO extends CoreBundle {
  val IN_flagUop = Flipped(Valid(new FlagUop))
  val IN_trapCSR = Flipped(new TrapCSR)

  val OUT_flush = Bool()
  val OUT_CSRCtrl = new CSRCtrl
  val OUT_redirect = new RedirectSignal
  val OUT_TLBFlush = Bool()
  val OUT_flushICache = Bool()
  val OUT_flushDCache = Bool()
}

class CSRCtrl extends CoreBundle {
  // * trap
  val trap  = Bool()
  val intr  = Bool()
  val pc    = UInt(XLEN.W)
  val cause = UInt(6.W)
  // * ertn
  val ertn  = Bool()
}
// 
class FlagHandler extends CoreModule {
  val io = IO(new FlagHandlerIO)

  val flag = io.IN_flagUop.bits.flag
  val decodeFlag = io.IN_flagUop.bits.rd(3, 0)

  val flush = Reg(Bool())
  val TLBFlush = Reg(Bool())
  val flushICache = Reg(Bool())
  val flushDCache = Reg(Bool())
  val redirect = Reg(new RedirectSignal)
  val CSRCtrl = Reg(new CSRCtrl)
  val CSRCtrlNext = WireInit(0.U.asTypeOf(new CSRCtrl))

  flush := false.B
  TLBFlush := false.B
  flushICache := false.B
  flushDCache := false.B
  redirect.pc := 0.U
  redirect.valid := false.B
  CSRCtrl := CSRCtrlNext
  val priv = io.IN_trapCSR.priv
  val xtvec = io.IN_trapCSR.eentry

  when (io.IN_flagUop.valid) {
    when (FlagOp.isRedirect(flag)) {
      redirect.valid := true.B
      redirect.pc := io.IN_flagUop.bits.target
      flush := true.B
    }
    when(flag === FlagOp.DECODE_FLAG) {
      when(decodeFlag === DecodeFlagOp.INTERRUPT) {
        when(io.IN_trapCSR.interrupt) {
          redirect.valid := true.B
          redirect.pc := xtvec
          
          flush := true.B
          
          CSRCtrlNext.trap := true.B
          CSRCtrlNext.intr := true.B
          CSRCtrlNext.cause := Exception.INT
          CSRCtrlNext.pc := io.IN_flagUop.bits.pc
        }.otherwise {
          redirect.valid := true.B
          redirect.pc := io.IN_flagUop.bits.pc

          flush := true.B
        }
      }
      when(decodeFlag === DecodeFlagOp.ERTN) {
        redirect.valid := true.B
        redirect.pc := io.IN_trapCSR.era
        flush := true.B
        
        CSRCtrlNext.pc := io.IN_flagUop.bits.pc
        CSRCtrlNext.ertn := true.B
      }
      when(decodeFlag === DecodeFlagOp.SFENCE_VMA) {
        redirect.valid := true.B
        redirect.pc := io.IN_flagUop.bits.pc + 4.U
        flush := true.B
  
        TLBFlush := true.B
      }
      when(decodeFlag === DecodeFlagOp.FENCE_I) {
        // * Invalidate ICache, write back all DCache
        redirect.valid := true.B
        redirect.pc := io.IN_flagUop.bits.pc + 4.U
        flush := true.B

        flushICache := true.B
        flushDCache := true.B
      }
      when(decodeFlag === DecodeFlagOp.INST_PAGE_FAULT) {
        redirect.valid := true.B
        redirect.pc := xtvec
        flush := true.B

        CSRCtrlNext.trap := true.B
        CSRCtrlNext.cause := Exception.PIF
        CSRCtrlNext.pc := io.IN_flagUop.bits.pc
      }
    }
    when((flag >= FlagOp.PIL && flag <= FlagOp.ALE) || 
         (flag >= FlagOp.SYS && flag <= FlagOp.IPE)) {
      redirect.valid := true.B
      redirect.pc := xtvec
      flush := true.B
      
      CSRCtrlNext.trap := true.B
      CSRCtrlNext.cause := flag
      CSRCtrlNext.pc := io.IN_flagUop.bits.pc
    }
    when(flag === FlagOp.TLBR) {
      redirect.valid := true.B
      redirect.pc := io.IN_trapCSR.tlbrentry
      flush := true.B

      CSRCtrlNext.trap := true.B
      CSRCtrlNext.cause := Exception.TLBR
      CSRCtrlNext.pc := io.IN_flagUop.bits.pc
    }
  }

  io.OUT_flush := flush
  io.OUT_redirect := redirect  
  io.OUT_CSRCtrl := CSRCtrl
  io.OUT_TLBFlush := TLBFlush
  io.OUT_flushICache := flushICache
  io.OUT_flushDCache := flushDCache
}