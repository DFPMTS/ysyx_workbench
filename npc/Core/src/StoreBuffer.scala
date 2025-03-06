import chisel3._
import chisel3.util._
import utils._

class StoreAck extends CoreBundle {
  val index = UInt(STQ_IDX_W)
  val resp = UInt(2.W)
}

class StoreBufferIO extends CoreBundle {
  val IN_storeUop = Flipped(Decoupled(new AGUUop))
  val OUT_storeUop = Valid(new AGUUop)
  val IN_storeAck = Flipped(Valid(new StoreAck))

  val IN_storeBypassReq = Flipped(new StoreBypassReq)
  val OUT_storeBypassResp = new StoreBypassResp
}

class StoreBuffer extends CoreModule {
  val io = IO(new StoreBufferIO)

  val uop = Reg(new AGUUop)
  val uopValid = RegInit(false.B)
  val uopIssued = Reg(Bool())

  io.IN_storeUop.ready := !uopValid

  when(io.IN_storeUop.fire) {
    uop := io.IN_storeUop.bits
    uopValid := true.B
    uopIssued := false.B
  }.otherwise {
    uopIssued := true.B
  }

  def getWmask(aguUop: AGUUop): UInt = {
    val memLen = aguUop.opcode(2, 1)
    val addrOffset = aguUop.addr(log2Up(XLEN/8) - 1, 0)
    val wmask = MuxLookup(memLen, 0.U(4.W))(
      Seq(
        0.U(2.W) -> "b0001".U,
        1.U(2.W) -> "b0011".U,
        2.U(2.W) -> "b1111".U
      )
    ) << addrOffset
    wmask(3, 0)
  }

  def getShiftedData(aguUop: AGUUop): UInt = {
    val addrOffset = aguUop.addr(log2Up(XLEN/8) - 1, 0)
    (aguUop.wdata << (addrOffset << 3))(XLEN - 1, 0)
  }

  def addrMatch (addr1: UInt, addr2: UInt): Bool = {
    addr1(XLEN - 1, 2) === addr2(XLEN - 1, 2)
  }

  // * Store bypass logic
  val bypassDataNext = Wire(Vec(4, UInt(8.W)))
  bypassDataNext := 0.U.asTypeOf(bypassDataNext)
  val bypassDataMaskNext = Wire(Vec(4, Bool()))
  bypassDataMaskNext := 0.U.asTypeOf(bypassDataMaskNext)
  val bypassData = RegNext(bypassDataNext)
  val bypassDataMask = RegNext(bypassDataMaskNext)
  io.OUT_storeBypassResp.data := bypassData.asTypeOf(UInt(32.W))
  io.OUT_storeBypassResp.mask := bypassDataMask.asUInt
  val wmask = getWmask(uop)
  val shiftedData = getShiftedData(uop)
  
  when(addrMatch(io.IN_storeBypassReq.addr, uop.addr) && uopValid) {
    val uopWmask = getWmask(uop)
    val uopShiftedData = getShiftedData(uop)
    for (i <- 0 until 4) {
      when(uopWmask(i)) {
        bypassDataNext(i) := uopShiftedData((i + 1) * 8 - 1, i * 8)
        bypassDataMaskNext(i) := true.B
      }
    }
  }

  // * Issue logic
  io.OUT_storeUop.valid := uopValid && !uopIssued
  io.OUT_storeUop.bits := uop

  when(io.IN_storeAck.valid) {
    // * success
    when(io.IN_storeAck.bits.resp === 0.U) {
      uopValid := false.B
    }.otherwise {
      uopIssued := false.B
    }
  }
}