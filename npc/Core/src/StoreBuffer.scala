import chisel3._
import chisel3.util._
import utils._

class StoreAck extends CoreBundle {
  val index = UInt(STQ_IDX_W)
  val resp = UInt(2.W)
}

class StoreLine extends CoreBundle {
  val data = Vec(STORE_LINE_B / 4, Vec(4 ,UInt(8.W)))
  val mask = Vec(STORE_LINE_B / 4, Vec(4, Bool()))

  def coalesceStore(aguUop: AGUUop, isNew: Bool): Unit = {
    val wordOffset = ( if(STORE_LINE_B == 4) 0.U else aguUop.addr(log2Up(STORE_LINE_B) - 1, 2) )
    when(isNew) {
      mask := 0.U.asTypeOf(mask)
    }
    for (i <- 0 until 4) {
      when(aguUop.mask(i)) {
        data(wordOffset)(i) := aguUop.wdata((i + 1) * 8 - 1, i * 8)
        mask(wordOffset)(i) := true.B
      }
    }
  }
}

class StoreBufferIO extends CoreBundle {
  val IN_storeUop = Flipped(Decoupled(new AGUUop))
  val OUT_storeUop = Decoupled(new AGUUop)
  val OUT_storeLine = new StoreLine
  val IN_storeAck = Flipped(Valid(new StoreAck))
  val OUT_storeBufferEmpty = Bool()

  val IN_storeBypassReq = Flipped(new StoreBypassReq)
  val OUT_storeBypassResp = new StoreBypassResp

  val OUT_retireStqPtr = RingBufferPtr(STQ_SIZE)
}

class StoreBuffer extends CoreModule {
  val io = IO(new StoreBufferIO)

  assert(STORE_LINE_B >= 4, "STORE_LINE_B must be at least 4 bytes")
  assert(STORE_LINE_B <= CACHE_LINE_B, "STORE_LINE_B must be less than or equal to CACHE_LINE_B")
  assert(isPow2(STORE_LINE_B), "STORE_LINE_B must be a power of 2")

  val uop = Reg(new AGUUop)
  val storeLine =Reg(new StoreLine)
  val uopValid = RegInit(false.B)
  val uopIssued = Reg(Bool())

  def storeLineAddrMatch (addr1: UInt, addr2: UInt): Bool = {
    addr1(XLEN - 1, log2Up(STORE_LINE_B)) === addr2(XLEN - 1, log2Up(STORE_LINE_B))
  }

  // * Store bypass logic
  val bypassDataNext = Wire(Vec(4, UInt(8.W)))
  bypassDataNext := 0.U.asTypeOf(bypassDataNext)
  val bypassDataMaskNext = Wire(Vec(4, Bool()))
  bypassDataMaskNext := 0.U.asTypeOf(bypassDataMaskNext)
  // val bypassData = RegNext(bypassDataNext)
  // val bypassDataMask = RegNext(bypassDataMaskNext)
  val bypassData = bypassDataNext
  val bypassDataMask = bypassDataMaskNext
  io.OUT_storeBypassResp.data := bypassData.asTypeOf(UInt(32.W))
  io.OUT_storeBypassResp.mask := bypassDataMask.asUInt
  io.OUT_storeBypassResp.notReady := 0.U
  
  when(storeLineAddrMatch(io.IN_storeBypassReq.addr, uop.addr) && uopValid) {
    val reqAddrWordOffset = ( if(STORE_LINE_B == 4) 0.U else io.IN_storeBypassReq.addr(log2Up(STORE_LINE_B) - 1, 2) )
    val uopWmask = storeLine.mask(reqAddrWordOffset)
    val uopShiftedData = storeLine.data(reqAddrWordOffset)
    for (i <- 0 until 4) {
      when(uopWmask(i)) {
        bypassDataNext(i) := uopShiftedData(i)
        bypassDataMaskNext(i) := true.B
      }
    }
  }

  // * Issue logic
  io.OUT_storeUop.valid := uopValid && !uopIssued
  io.OUT_storeUop.bits := uop
  io.OUT_storeBufferEmpty := !uopValid

  val storeBufferPtr = RegInit(RingBufferPtr(STQ_SIZE, 0.U, 0.U))

  val retireStqPtr = RegInit(RingBufferPtr(STQ_SIZE, 0.U, 0.U))
  io.OUT_retireStqPtr := retireStqPtr

  when(io.IN_storeAck.valid) {
    // * success
    when(io.IN_storeAck.bits.resp === 0.U) {
      uopValid := false.B
      retireStqPtr := storeBufferPtr
    }.otherwise {
      uopIssued := false.B
    }
  }

  def canCoalesceStore(inAGUUop: AGUUop) = {
    storeLineAddrMatch(inAGUUop.addr, uop.addr) &&
    !uop.isUncached &&
    !inAGUUop.isUncached
  }

  val uopWillEmpty = !uopValid || (io.IN_storeAck.valid && io.IN_storeAck.bits.resp === 0.U)

  io.IN_storeUop.ready := uopWillEmpty || (!uopIssued && canCoalesceStore(io.IN_storeUop.bits))

  when(io.IN_storeUop.fire) {
    storeBufferPtr := storeBufferPtr + 1.U
    storeLine.coalesceStore(io.IN_storeUop.bits, uopWillEmpty)
  }

  io.OUT_storeLine := storeLine

  when(io.IN_storeUop.fire && uopWillEmpty) {
    uop := io.IN_storeUop.bits
    uopValid := true.B
    uopIssued := false.B
  }.otherwise {
    when(io.OUT_storeUop.fire) {
      uopIssued := true.B
    }
  }
}