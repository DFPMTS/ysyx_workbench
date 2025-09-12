import chisel3._
import chisel3.util._
import utils._
import os.read.inputStream

class PTWUop extends CoreBundle {
  val addr = UInt(XLEN.W)
}

class PTWIO extends CoreBundle {
  val IN_PTWReq = Flipped(Vec(2, Decoupled(new PTWReq)))
  val OUT_PTWResp = Valid(new PTWResp)
  val IN_writebackUop = Flipped(Valid(new WritebackUop)) // * Writeback uop
  val IN_VMCSR = Flipped(new VMCSR) // * Virtual memory CSR

  val OUT_PTWUop = Decoupled(new PTWUop)
}

// * Page table walker (PTW)
// * sv32 page table
class PTW extends CoreModule {
  val io = IO(new PTWIO)

  val sIdle :: sL1 :: sL0 :: Nil = Enum(3)

  val state = RegInit(sIdle)

  val vpn = Reg(UInt(PAGE_NR_LEN.W))
  val id  = Reg(UInt(1.W))
  val pte = Reg(new PTE)
  val inPTE = io.IN_writebackUop.bits.data.asTypeOf(new PTE)
  val nextLevel = inPTE.v && !inPTE.r && !inPTE.w && !inPTE.x  // * Next level page table
  val hasLoadRepl = io.IN_writebackUop.valid && io.IN_writebackUop.bits.dest === Dest.PTW

  val ptwReq = Wire(Decoupled(new PTWReq))
  val reqId  = Wire(UInt(1.W))
  when(io.IN_PTWReq(1).valid) {
    ptwReq <> io.IN_PTWReq(1)
    io.IN_PTWReq(0).ready := false.B;
    reqId := 1.U
  }.otherwise {
    ptwReq <> io.IN_PTWReq(0)
    io.IN_PTWReq(1).ready := false.B;
    reqId := 0.U
  }

  val stateNext = MuxLookup(state, sIdle) (Seq(
    (sIdle -> Mux(ptwReq.valid, sL1, sIdle)),
    (sL1 -> Mux(hasLoadRepl, Mux(nextLevel, sL0, sIdle), sL1)),
    (sL0 -> Mux(hasLoadRepl, sIdle, sL0))
  ))
  state := stateNext

  val vpn1 = vpn(PAGE_NR_LEN-1, 10)
  val vpn0 = vpn(9, 0)

  val PTWUopValid = RegInit(false.B)
  when((state === sIdle && stateNext === sL1) ||
       (state === sL1 && stateNext === sL0)) {
    PTWUopValid := true.B
  }
  when(io.OUT_PTWUop.fire) {
    PTWUopValid := false.B
  }
  io.OUT_PTWUop.bits.addr := Mux(state === sL1, Cat(io.IN_VMCSR.rootPPN, vpn1, 0.U(2.W)), Cat(pte.ppn1, pte.ppn0, vpn0, 0.U(2.W)))
  io.OUT_PTWUop.valid := PTWUopValid

  when(ptwReq.fire) {
    vpn := ptwReq.bits.vpn
    id := reqId
  }

  when(hasLoadRepl) {
    pte := io.IN_writebackUop.bits.data.asTypeOf(new PTE)
  }
  
  ptwReq.ready := state === sIdle
  val finOnL1 = state === sL1 && hasLoadRepl && !nextLevel
  val finOnL0 = state === sL0 && hasLoadRepl
  val translateFin = finOnL1 || finOnL0
  
  io.OUT_PTWResp.valid := translateFin
  io.OUT_PTWResp.bits.isSuper := finOnL1
  io.OUT_PTWResp.bits.pte := io.IN_writebackUop.bits.data.asTypeOf(new PTE)
  io.OUT_PTWResp.bits.vpn := vpn
  io.OUT_PTWResp.bits.id := id
}