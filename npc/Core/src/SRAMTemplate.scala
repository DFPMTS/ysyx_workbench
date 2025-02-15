import chisel3._
import chisel3.util._
import utils._


class SRAMTemplateR(N: Int, width: Int, writeWidth: Int) extends CoreBundle {
  val en = Bool()
  val addr = UInt(log2Up(N).W)
  val rdata = Flipped(UInt(width.W))
  def apply(paddr: UInt, en: Bool) = {
    this.addr := paddr(log2Up(N) - 1 + log2Up(CACHE_LINE), log2Up(CACHE_LINE))
    this.en := en
  }
}

class SRAMTemplateRW(N: Int, width: Int, writeWidth: Int) extends SRAMTemplateR(N, width, writeWidth) {
  val write = Bool()
  val wdata = UInt(width.W)
  val wmask = UInt((width / writeWidth).W)
  def apply(paddr: UInt, write: UInt, mask: UInt, data: UInt, en: Bool) = {
    this.addr := paddr(log2Up(N) - 1 + log2Up(CACHE_LINE), log2Up(CACHE_LINE))
    this.wmask := mask
    this.write := write
    this.wdata := data
    this.en := en
  }
}

// * N: 数量 width: 元素宽度 writeWidth: wmask每一位对应的宽度
class SRAMTemplateIO(N: Int, width: Int, writeWidth: Int) extends CoreBundle {  
  val r = Flipped(new SRAMTemplateR(N, width, writeWidth))
  val rw = Flipped(new SRAMTemplateRW(N, width, writeWidth))
}


class SRAMTemplate(N: Int, width: Int, writeWidth: Int) extends CoreModule {
  assert(width % writeWidth == 0)
  val io = IO(new SRAMTemplateIO(N, width, writeWidth))

  val array = SyncReadMem(N, Vec(width / writeWidth, UInt(writeWidth.W)))

  val wDataVec = io.rw.wdata.asTypeOf(Vec(width / writeWidth, UInt(writeWidth.W)))

  // * Port0: R 读通道
  val rDataVec = Wire(Vec(width / writeWidth, UInt(writeWidth.W)))
  rDataVec := array.read(io.r.addr, io.r.en).asTypeOf(Vec(width / writeWidth, UInt(writeWidth.W)))
  // * try bypass
  when (io.r.addr === io.rw.addr && io.rw.en && io.rw.write) {
    for (i <- 0 until width / writeWidth) {
      when (io.rw.wmask(i)) {
        rDataVec(i) := wDataVec(i)
      }
    }
  }
  io.r.rdata := rDataVec.asUInt

  // * Port1: RW 读写通道
  io.rw.rdata := array.readWrite(io.rw.addr, wDataVec, io.rw.wmask.asBools, io.rw.en, io.rw.write).asUInt
}
