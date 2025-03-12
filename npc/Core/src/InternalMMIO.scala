import chisel3._
import chisel3.util._
import utils._

class InternalMMIOIO extends CoreBundle {
  val IN_mmioReq = Flipped(new MMIOReq)
  val OUT_mmioResp = new MMIOResp

  val OUT_mtime = UInt(64.W)
  val OUT_MTIP = Bool()
}


class MMIOReq extends CoreBundle {
  val addr = UInt(XLEN.W)
  val ren = Bool()
  val wdata = UInt(XLEN.W)    
  val wen = Bool()
}

class MMIOResp extends CoreBundle {
  val data = UInt(XLEN.W)
}

// * 1-cycle latency
class InternalMMIO extends CoreModule {
  val io = IO(new InternalMMIOIO)

  // MMIO registers
  val mtime = RegInit(0.U(64.W))
  val mtimecmp = RegInit(0.U(64.W))
  val msip = RegInit(0.U(1.W))
  mtime := mtime + 1.U

  val CLINT_BASE = 0x11000000L.U(32.W)
  val MTIME_OFFSET = 0xbff8.U
  val MTIMECMP_OFFSET = 0x4000.U
  val MSIP_OFFSET = 0x0.U

  val mtimel = mtime(31, 0)
  val mtimeh = mtime(63, 32)
  val mtimecmpl = mtimecmp(31, 0)
  val mtimecmph = mtimecmp(63, 32)


  val mmioReadData = Reg(UInt(XLEN.W))  
  io.OUT_mmioResp.data := mmioReadData

  def addrMatch(addr: UInt, base: UInt, offset: UInt) = {
    addr(31, 2) === (base + offset)(31, 2)
  }

  when(io.IN_mmioReq.ren) {
    // Read
    when(io.IN_mmioReq.addr(31, 24) === 0x11.U) {
      val raddr = io.IN_mmioReq.addr
      when(addrMatch(raddr, CLINT_BASE, MTIME_OFFSET)) {
        mmioReadData := mtimel
      }.elsewhen(addrMatch(raddr, CLINT_BASE, MTIME_OFFSET + 4.U)) {
        mmioReadData := mtimeh
      }.elsewhen(addrMatch(raddr, CLINT_BASE, MTIMECMP_OFFSET)) {
        mmioReadData := mtimecmpl
      }.elsewhen(addrMatch(raddr, CLINT_BASE, MTIMECMP_OFFSET + 4.U)) {
        mmioReadData := mtimecmph
      }.elsewhen(addrMatch(raddr, CLINT_BASE, MSIP_OFFSET)) {
        mmioReadData := msip
      }
    }
  }.elsewhen(io.IN_mmioReq.wen) {
    // Write
    when(io.IN_mmioReq.addr(31, 24) === 0x11.U) {
      val waddr = io.IN_mmioReq.addr
      val wdata = io.IN_mmioReq.wdata
      when(addrMatch(waddr, CLINT_BASE, MTIME_OFFSET)) {
        mtime := Cat(mtime(63, 32),  wdata)
      }.elsewhen(addrMatch(waddr, CLINT_BASE, MTIME_OFFSET + 4.U)) {
        mtime := Cat(wdata, mtime(31, 0))
      }.elsewhen(addrMatch(waddr, CLINT_BASE, MTIMECMP_OFFSET)) {
        mtimecmp := Cat(mtimecmp(63, 32), wdata)
      }.elsewhen(addrMatch(waddr, CLINT_BASE, MTIMECMP_OFFSET + 4.U)) {
        mtimecmp := Cat(wdata, mtimecmp(31, 0))
      }.elsewhen(addrMatch(waddr, CLINT_BASE, MSIP_OFFSET)) {
        msip := wdata(0)
      }
    }
  }

  io.OUT_mtime := mtime
  io.OUT_MTIP := mtime >= mtimecmp
}