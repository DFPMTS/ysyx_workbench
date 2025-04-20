import chisel3._
import chisel3.util._
import utils._

/* 
class DCacheIO extends CoreBundle {
  val IN_tagReq = Flipped(Decoupled(new DTagReq))
  val OUT_tagResp = new DTagResp
  val IN_dataReq = Flipped(Decoupled(new DDataReq))
  val OUT_dataResp = new DDataResp

  val IN_ctrlDataRead = Flipped(Valid(new DDataReq)) 
  val OUT_ctrlDataResp = new DDataResp
  val IN_ctrlDataWrite = Flipped(Valid(new DDataReq))
}

class DCache extends CoreModule {
  val io = IO(new DCacheIO)

  val tagArray = Module(new SRAMTemplate(DCACHE_SETS, DCACHE_WAYS, DCACHE_TAG + 1, DCACHE_TAG + 1))  
  val dataArray = Module(new SRAMTemplate(DCACHE_SETS, DCACHE_WAYS, CACHE_LINE_B * 8, 8))

  // * Src: 0 -> LSU, 1 -> Cache Controller

  // * tag rw 
  io.IN_tagReq.ready := true.B
  // * arbitration  
  tagArray.io.rw(io.IN_tagReq.bits.addr, io.IN_tagReq.bits.write, io.IN_tagReq.bits.way, io.IN_tagReq.bits.write, io.IN_tagReq.bits.data.asUInt, io.IN_tagReq.valid)
  tagArray.io.r(0.U, false.B)
  // * resp
  io.OUT_tagResp := tagArray.io.rw.rdata.asTypeOf(new DTagResp)
  
  // * data r
  dataArray.io.r(io.IN_ctrlDataRead.bits.addr, io.IN_ctrlDataRead.valid)
  
  // * data rw
  io.IN_dataReq.ready := true.B
  dataArray.io.rw(io.IN_dataReq.bits.addr, io.IN_dataReq.bits.write, io.IN_dataReq.bits.way, io.IN_dataReq.bits.wmask, io.IN_dataReq.bits.data, io.IN_dataReq.valid)
  when(io.IN_ctrlDataWrite.valid) {
    dataArray.io.rw(io.IN_ctrlDataWrite.bits.addr, io.IN_ctrlDataWrite.bits.write, io.IN_ctrlDataWrite.bits.way, io.IN_ctrlDataWrite.bits.wmask, io.IN_ctrlDataWrite.bits.data, io.IN_ctrlDataWrite.valid)
    io.IN_dataReq.ready := false.B
  }

  // * resp
  io.OUT_dataResp := dataArray.io.rw.rdata.asTypeOf(new DDataResp)
  io.OUT_ctrlDataResp := dataArray.io.r.rdata.asTypeOf(new DDataResp)
}
 */

/* 
class DTagReq extends CoreBundle {
  val addr = UInt(XLEN.W)
  val write = Bool()
  val way = UInt(log2Up(DCACHE_WAYS).W)
  val data = new DTag
}

class DTagResp extends CoreBundle {
  val tags = Vec(DCACHE_WAYS, new DTag)
}

class DTag extends CoreBundle {
  val valid = Bool()
  val tag = UInt(DCACHE_TAG.W)
}

class DDataReq extends CoreBundle {
  val addr = UInt(XLEN.W)
  val write = Bool()
  val way = UInt(log2Up(DCACHE_WAYS).W)
  val wmask = UInt(CACHE_LINE_B.W)
  val data = UInt((CACHE_LINE_B * 8).W)
}

class DDataResp extends CoreBundle {
  val data = Vec(DCACHE_WAYS, Vec(CACHE_LINE_B/4, UInt(32.W)))
}
 */

class ITag extends CoreBundle {
  val valid = Bool()
  val tag = UInt(ICACHE_TAG.W)
}

class ITagRead extends CoreBundle {
  val addr = UInt(XLEN.W)
}

class ITagWrite extends CoreBundle {
  val addr = UInt(XLEN.W)
  val way = UInt(log2Up(ICACHE_WAYS).W)
  val data = new ITag
}

class ITagResp extends CoreBundle {
  val tags = Vec(ICACHE_WAYS, new ITag)
}

class IDataRead extends CoreBundle {
  val addr = UInt(XLEN.W)
}

class IDataWrite extends CoreBundle {
  val addr = UInt(XLEN.W)
  val way = UInt(log2Up(ICACHE_WAYS).W)
  val wmask = UInt(CACHE_LINE_B.W)
  val data = Vec(CACHE_LINE_B/4, UInt(32.W))
}

class IDataResp extends CoreBundle {
  val data = Vec(ICACHE_WAYS, Vec(CACHE_LINE_B/4, UInt(32.W)))
}

class ICacheIO extends CoreBundle {
  // * Tag (r/w by IFU)
  val IN_tagRead = Flipped(Valid(new ITagRead))
  val OUT_tagResp = new ITagResp

  val IN_tagWrite = Flipped(Valid(new ITagWrite))

  // * Data (r by IFU, w by Cache Controller)
  val IN_dataRead = Flipped(Valid(new IDataRead))
  val OUT_dataResp = new IDataResp

  val IN_ctrlDataWrite = Flipped(Valid(new IDataWrite))  
}

class NewICache extends CoreModule {
  val io = IO(new ICacheIO)

  val tagArray = Module(new SRAMTemplate(ICACHE_SETS, ICACHE_WAYS, ICACHE_TAG + 1, ICACHE_TAG + 1))  
  val dataArray = Module(new XilinxBRAM(ICACHE_SETS, ICACHE_WAYS, CACHE_LINE_B * 8, 8))

  // * Tag
  tagArray.io.r(io.IN_tagRead.bits.addr, CACHE_LINE_B, io.IN_tagRead.valid)
  tagArray.io.rw(io.IN_tagWrite.bits.addr, CACHE_LINE_B, true.B, io.IN_tagWrite.bits.way, true.B, io.IN_tagWrite.bits.data.asUInt, io.IN_tagWrite.valid)

  io.OUT_tagResp := tagArray.io.r.rdata.asTypeOf(new ITagResp)

  // * Data
  dataArray.io.r(io.IN_dataRead.bits.addr, CACHE_LINE_B, io.IN_dataRead.valid)
  dataArray.io.rw(io.IN_ctrlDataWrite.bits.addr, CACHE_LINE_B, true.B, io.IN_ctrlDataWrite.bits.way, io.IN_ctrlDataWrite.bits.wmask, io.IN_ctrlDataWrite.bits.data.asUInt, io.IN_ctrlDataWrite.valid)

  io.OUT_dataResp := dataArray.io.r.rdata.asTypeOf(new IDataResp)

}
