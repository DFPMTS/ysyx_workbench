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
}

class CSRCtrl extends CoreBundle {
  // * trap
  val trap  = Bool()
  val intr  = Bool()
  val pc    = UInt(XLEN.W)
  val cause = UInt(4.W)
  val delegate = Bool()
  // * mret/sret
  val mret  = Bool()
  val sret  = Bool()
}
// 
class FlagHandler extends CoreModule {
  val io = IO(new FlagHandlerIO)

  val flag = io.IN_flagUop.bits.flag
  val decodeFlag = io.IN_flagUop.bits.rd(3, 0)

  val flush = Reg(Bool())
  val TLBFlush = Reg(Bool())
  val redirect = Reg(new RedirectSignal)
  val CSRCtrl = Reg(new CSRCtrl)
  val CSRCtrlNext = WireInit(0.U.asTypeOf(new CSRCtrl))

  flush := false.B
  TLBFlush := false.B
  redirect.pc := 0.U
  redirect.valid := false.B
  CSRCtrl := CSRCtrlNext
  val priv = io.IN_trapCSR.priv
  val medeleg = io.IN_trapCSR.medeleg
  val mideleg = io.IN_trapCSR.mideleg
  val exceptionDelegate = priv < Priv.M && medeleg(CSRCtrlNext.cause)
  val interruptDelegate = priv < Priv.M && mideleg(CSRCtrlNext.cause)
  val delegate = Mux(CSRCtrlNext.intr, interruptDelegate, exceptionDelegate)
  val xtvec = Mux(delegate, io.IN_trapCSR.stvec, io.IN_trapCSR.mtvec)
  
  CSRCtrlNext.delegate := delegate

  when (io.IN_flagUop.valid) {
    when (FlagOp.isRedirect(flag)) {
      redirect.valid := true.B
      redirect.pc := io.IN_flagUop.bits.target
      flush := true.B
    }
    when(flag === FlagOp.DECODE_FLAG) {
      when(decodeFlag === DecodeFlagOp.INTERRUPT && io.IN_trapCSR.interrupt) {
        redirect.valid := true.B
        redirect.pc := xtvec
  
        flush := true.B
  
        CSRCtrlNext.trap := true.B
        CSRCtrlNext.intr := true.B
        CSRCtrlNext.cause := io.IN_trapCSR.interruptCause
        CSRCtrlNext.pc := io.IN_flagUop.bits.pc
      }
      when(decodeFlag === DecodeFlagOp.EBREAK) {
        redirect.valid := true.B
        redirect.pc := xtvec
        flush := true.B
        CSRCtrlNext.trap := true.B
        CSRCtrlNext.cause := Exception.BREAKPOINT
        CSRCtrlNext.pc := io.IN_flagUop.bits.pc
      }
      when(decodeFlag === DecodeFlagOp.ECALL) {
        redirect.valid := true.B
        redirect.pc := xtvec
        flush := true.B
        CSRCtrlNext.trap := true.B
        CSRCtrlNext.cause := 8.U + io.IN_trapCSR.priv
        CSRCtrlNext.pc := io.IN_flagUop.bits.pc
      }

      when(decodeFlag === DecodeFlagOp.MRET) {
        redirect.valid := true.B
        redirect.pc := io.IN_trapCSR.mepc
        flush := true.B
        
        CSRCtrlNext.pc := io.IN_flagUop.bits.pc
        CSRCtrlNext.mret := true.B
      }
      when(decodeFlag === DecodeFlagOp.SRET) {
        redirect.valid := true.B
        redirect.pc := io.IN_trapCSR.sepc
        flush := true.B
        
        CSRCtrlNext.pc := io.IN_flagUop.bits.pc
        CSRCtrlNext.sret := true.B
      }
      when(decodeFlag === DecodeFlagOp.SFENCE_VMA) {
        redirect.valid := true.B
        redirect.pc := io.IN_flagUop.bits.pc + 4.U
        flush := true.B
  
        TLBFlush := true.B
      }
      when(decodeFlag === DecodeFlagOp.INST_PAGE_FAULT) {
        redirect.valid := true.B
        redirect.pc := xtvec
        flush := true.B

        CSRCtrlNext.trap := true.B
        CSRCtrlNext.cause := Exception.INST_PAGE_FAULT
        CSRCtrlNext.pc := io.IN_flagUop.bits.pc
      }
    }
    when(flag === FlagOp.ILLEGAL_INST ||
         (flag >= FlagOp.LOAD_ADDR_MISALIGNED && flag <= FlagOp.STORE_ACCESS_FAULT) ||
         flag === FlagOp.LOAD_PAGE_FAULT || flag === FlagOp.STORE_PAGE_FAULT) {
      redirect.valid := true.B
      redirect.pc := xtvec
      flush := true.B
      
      CSRCtrlNext.trap := true.B
      CSRCtrlNext.cause := flag
      CSRCtrlNext.pc := io.IN_flagUop.bits.pc
    }
  }

  io.OUT_flush := flush
  io.OUT_redirect := redirect  
  io.OUT_CSRCtrl := CSRCtrl
  io.OUT_TLBFlush := TLBFlush
}