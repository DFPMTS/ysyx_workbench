import chisel3._
import chisel3.util._
import utils._

class FlagHandlerIO extends CoreBundle {
  val IN_flagUop = Flipped(Valid(new FlagUop))
  val IN_trapCSR = Flipped(new TrapCSR)

  val OUT_flush = Bool()
  val OUT_CSRCtrl = new CSRCtrl
  val OUT_redirect = new RedirectSignal
}

class CSRCtrl extends CoreBundle {
  // * trap
  val trap  = Bool()
  val pc    = UInt(XLEN.W)
  val cause = UInt(5.W)
  val delegate = Bool()
  // * mret/sret
  val mret  = Bool()
  val sret  = Bool()
}
// 
class FlagHandler extends CoreModule {
  val io = IO(new FlagHandlerIO)

  val flag = io.IN_flagUop.bits.flag

  val flush = Reg(Bool())
  val redirect = Reg(new RedirectSignal)
  val CSRCtrl = Reg(new CSRCtrl)
  val CSRCtrlNext = WireInit(0.U.asTypeOf(new CSRCtrl))

  flush := false.B
  redirect.pc := 0.U
  redirect.valid := false.B
  CSRCtrl := CSRCtrlNext
  val priv = io.IN_trapCSR.priv
  val medeleg = io.IN_trapCSR.medeleg
  val exceptionDelegate = priv < Priv.M && medeleg(CSRCtrlNext.cause)
  val xtvec = Mux(exceptionDelegate, io.IN_trapCSR.stvec, io.IN_trapCSR.mtvec)
  
  CSRCtrlNext.delegate := exceptionDelegate

  when (io.IN_flagUop.valid) {
    when (flag === FlagOp.MISPREDICT) {
      redirect.valid := true.B
      redirect.pc := io.IN_flagUop.bits.target
      flush := true.B
    }
    when(flag === FlagOp.ECALL) {
      redirect.valid := true.B
      redirect.pc := xtvec
      flush := true.B

      CSRCtrlNext.trap := true.B
      CSRCtrlNext.cause := FlagOp.ECALL + io.IN_trapCSR.priv
      CSRCtrlNext.pc := io.IN_flagUop.bits.pc
    }
    when((flag >= FlagOp.INST_ACCESS_FAULT && flag <= FlagOp.STORE_ACCESS_FAULT) ||
         flag === FlagOp.INST_PAGE_FAULT || flag === FlagOp.LOAD_PAGE_FAULT ||
         flag === FlagOp.STORE_PAGE_FAULT) {
      redirect.valid := true.B
      redirect.pc := xtvec
      flush := true.B
      
      CSRCtrlNext.trap := true.B
      CSRCtrlNext.cause := flag
      CSRCtrlNext.pc := io.IN_flagUop.bits.pc
    }
    when(flag === FlagOp.MRET) {
      redirect.valid := true.B
      redirect.pc := io.IN_trapCSR.mepc
      flush := true.B
      
      CSRCtrlNext.mret := true.B
    }
    when(flag === FlagOp.SRET) {
      redirect.valid := true.B
      redirect.pc := io.IN_trapCSR.sepc
      flush := true.B
      
      CSRCtrlNext.sret := true.B
    }
  }

  io.OUT_flush := flush
  io.OUT_redirect := redirect  
  io.OUT_CSRCtrl := CSRCtrl
}