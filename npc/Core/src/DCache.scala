import chisel3._
import chisel3.util._
import utils._

class DCacheIO extends CoreBundle {
  val IN_tagRead = Flipped(Decoupled(new DTagReq))
  val OUT_tagResp = new DTagResp
  val IN_tagWrite = Flipped(Decoupled(new DTagReq))

  val IN_dataRead = Flipped(Decoupled(new DDataReq))
  val OUT_dataResp = new DDataResp
  val IN_dataWrite = Flipped(Decoupled(new DDataReq))

  val IN_ctrlDataRead = Flipped(Valid(new DDataReq)) 
  val OUT_ctrlDataResp = new DDataResp
  val IN_ctrlDataWrite = Flipped(Valid(new DDataReq))
}

class DCache extends CoreModule {
  val io = IO(new DCacheIO)

  val tagArray = Seq.fill(DCACHE_WAYS)(Module(new XilinxBRAM(DCACHE_SETS, DCACHE_TAG + 1, DCACHE_TAG + 1)))
  val dataArray = Seq.fill(DCACHE_WAYS)(Module(new XilinxBRAM(DCACHE_SETS, CACHE_LINE_B * 8, 8)))

  // * Src: 0 -> LSU, 1 -> Cache Controller

  // * tag rw 
  io.IN_tagRead.ready := true.B
  io.IN_tagWrite.ready := true.B
  // * arbitration  
  for(i <- 0 until DCACHE_WAYS) {
    tagArray(i).io.rw(io.IN_tagWrite.bits.addr, CACHE_LINE_B, io.IN_tagWrite.bits.way === i.U, io.IN_tagWrite.bits.way, io.IN_tagWrite.bits.write, io.IN_tagWrite.bits.data.asUInt, io.IN_tagWrite.valid)
    tagArray(i).io.r(io.IN_tagRead.bits.addr, CACHE_LINE_B, io.IN_dataRead.valid)
    // * resp
    io.OUT_tagResp.tags(i) := tagArray(i).io.r.rdata.asTypeOf(io.OUT_tagResp.tags(i))
  }
  
  
  io.IN_dataRead.ready := true.B
  io.IN_dataWrite.ready := true.B
  for (i <- 0 until DCACHE_WAYS) {
    // * data r
    dataArray(i).io.r(io.IN_dataRead.bits.addr, CACHE_LINE_B, io.IN_dataRead.valid)
    when(io.IN_ctrlDataRead.valid) {
      dataArray(i).io.r(io.IN_ctrlDataRead.bits.addr, CACHE_LINE_B, io.IN_ctrlDataRead.valid)
      io.IN_dataRead.ready := false.B
    }
    
    // * data rw
    dataArray(i).io.rw(io.IN_dataWrite.bits.addr, CACHE_LINE_B, io.IN_dataWrite.bits.way === i.U, io.IN_dataWrite.bits.way, io.IN_dataWrite.bits.wmask, io.IN_dataWrite.bits.data, io.IN_dataWrite.valid)
    
    when(io.IN_ctrlDataWrite.valid) {
      dataArray(i).io.rw(io.IN_ctrlDataWrite.bits.addr, CACHE_LINE_B, io.IN_ctrlDataWrite.bits.way === i.U, io.IN_ctrlDataWrite.bits.way, io.IN_ctrlDataWrite.bits.wmask, io.IN_ctrlDataWrite.bits.data, io.IN_ctrlDataWrite.valid)
      io.IN_dataWrite.ready := false.B
    }
      // * resp
    io.OUT_dataResp.data(i) := dataArray(i).io.r.rdata.asTypeOf(io.OUT_dataResp.data(i))
    io.OUT_ctrlDataResp.data(i) := dataArray(i).io.r.rdata.asTypeOf(io.OUT_ctrlDataResp.data(i))
  }
}