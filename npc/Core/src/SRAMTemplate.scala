import chisel3._
import chisel3.util._
import utils._
import firrtl.ir.ReadUnderWrite


class SRAMTemplateR(N: Int, ways: Int, width: Int, writeWidth: Int) extends CoreBundle {
  val en = Bool()
  val addr = UInt(log2Up(N).W)
  val rdata = Flipped(Vec(ways, UInt(width.W)))
  def apply(paddr: UInt, en: Bool) = {
    this.addr := paddr(log2Up(N) - 1 + log2Up(CACHE_LINE_B), log2Up(CACHE_LINE_B))
    this.en := en
  }
}

class SRAMTemplateRW(N: Int, ways: Int, width: Int, writeWidth: Int) extends SRAMTemplateR(N, ways, width, writeWidth) {
  val write = Bool()
  val wdata = UInt(width.W)
  val way = UInt(log2Up(ways).W)
  val wmask = UInt((width / writeWidth).W)
  def apply(paddr: UInt, write: UInt, way: UInt, mask: UInt, data: UInt, en: Bool) = {
    this.addr := paddr(log2Up(N) - 1 + log2Up(CACHE_LINE_B), log2Up(CACHE_LINE_B))
    this.wmask := mask
    this.way := way
    this.write := write
    this.wdata := data
    this.en := en
  }
}

// * N: 数量 width: 元素宽度 writeWidth: wmask每一位对应的宽度
class SRAMTemplateIO(N: Int, ways: Int, width: Int, writeWidth: Int) extends CoreBundle {  
  val r = Flipped(new SRAMTemplateR(N, ways, width, writeWidth))
  val rw = Flipped(new SRAMTemplateRW(N, ways, width, writeWidth))
}


class SRAMTemplate(N: Int, ways: Int, width: Int, writeWidth: Int) extends CoreModule {
  val io = IO(new SRAMTemplateIO(N, ways, width, writeWidth))

  assert(width % writeWidth == 0, "width must be multiple of writeWidth")

  val numEntries = width / writeWidth
  val PhysicalSet = Vec(ways * numEntries, UInt(writeWidth.W))
  val Line = Vec(numEntries, UInt(writeWidth.W))
  val Set = Vec(ways, Line)
  val array = SyncReadMem(N, PhysicalSet, ReadUnderWrite.New)

  val writeDataVec = Fill(ways, io.rw.wdata).asTypeOf(PhysicalSet) 
  val writeMaskVec = (io.rw.wmask << (io.rw.way * numEntries.U)).take(numEntries * ways)

  // * Port0: R 读通道
  io.r.rdata := array.read(io.r.addr, io.r.en).asTypeOf(io.r.rdata)

  // * Port1: RW 读写通道
  io.rw.rdata := array.readWrite(io.rw.addr, writeDataVec, writeMaskVec.asBools, io.rw.en, io.rw.write).asTypeOf(io.rw.rdata)
}
