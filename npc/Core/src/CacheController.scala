import chisel3._
import chisel3.util._
import utils._
import scala.language.implicitConversions
import coursier.internal.shaded.fastparse.ScriptWhitespace.whitespace

class CacheControllerResp extends CoreBundle {
  val data = UInt(32.W)
}

class MemLoadFoward extends CoreBundle {
  val addr = UInt(XLEN.W)
  val data = UInt((CACHE_LINE_B * 8).W)
  val uncached = Bool()
}

class CacheControllerIO extends CoreBundle {
  // * Cache Controller Uop
  val IN_cacheCtrlUop = Flipped(Vec(2, Decoupled(new CacheCtrlUop)))

  // * Cache Array Management
  // ** Data Cache Tag & Data
  val OUT_DDataRead = Valid(new DDataReq)
  val OUT_DDataWrite = Valid(new DDataReq)
  val IN_DDataResp = Flipped(new DDataResp)

  // ** Instruction Cache Tag & Data
  val OUT_MSHR = Vec(NUM_MSHR, new MSHR)

  // ** Forward load data and Uncached Load Resp
  val OUT_memLoadFoward = Valid(new MemLoadFoward)

  // ** Uncached Store Resp
  val OUT_uncacheStoreResp = Bool()

  // * -> MEM interface
  val OUT_axi = new AXI4(AXI_DATA_WIDTH, AXI_ADDR_WIDTH)
}

class MSHR extends CoreBundle {
  val valid = Bool()
  val uncached = Bool()
  val opcode = UInt(OpcodeWidth.W)
  val way = UInt(log2Up(DCACHE_WAYS).W)
  // * read from Mem
  val memReadAddr = UInt(XLEN.W)
  val needReadMem = Bool()
  // * write to Mem
  val memWriteAddr = UInt(XLEN.W)
  val needReadCache = Bool()
  val needWriteMem = Bool()
  
  val axiReadDone = Bool()
  val axiWriteDone = Bool()

  // * data
  val wdata = UInt(XLEN.W)
  val wmask = UInt(4.W)

  def loadAddrInFlight(addr: UInt) = {
    valid && 
    memReadAddr(XLEN - 1, log2Up(CACHE_LINE_B)) === addr(XLEN - 1, log2Up(CACHE_LINE_B)) &&
    !axiReadDone
  }

  def cacheLocation() = {
    Cat(way, memWriteAddr(log2Up(CACHE_LINE_B) + log2Up(DCACHE_SETS) - 1, log2Up(CACHE_LINE_B)))
  }
}

class CacheController extends CoreModule {
  val io = IO(new CacheControllerIO)

  val uopValidIndex = PriorityEncoder(io.IN_cacheCtrlUop.map(_.valid))

  val uop = io.IN_cacheCtrlUop(uopValidIndex).bits
  val uopValid = io.IN_cacheCtrlUop(uopValidIndex).valid

  val mshr = RegInit(VecInit(Seq.fill(NUM_MSHR)(0.U.asTypeOf(new MSHR))))
  val mshrFree = mshr.map(!_.valid)
  val mshrFreeIndex = PriorityEncoder(mshrFree)
  val canAllocateMSHR = mshrFree.reduce(_ || _)

  for (i <- 0 until 2) {
    io.IN_cacheCtrlUop(i).ready := false.B
  }
  io.IN_cacheCtrlUop(uopValidIndex).ready := canAllocateMSHR
  
  io.OUT_MSHR := mshr

  when(canAllocateMSHR && uopValid) {
    val newMSHR = Wire(new MSHR)
    mshr(mshrFreeIndex) := newMSHR

    newMSHR.valid := true.B
    newMSHR.way := uop.way
    newMSHR.memReadAddr := 0.U
    newMSHR.memWriteAddr := 0.U
    newMSHR.needReadCache := false.B
    newMSHR.uncached := false.B
    newMSHR.needReadMem := false.B
    newMSHR.needWriteMem := false.B
    newMSHR.axiReadDone := true.B
    newMSHR.axiWriteDone := true.B
    newMSHR.opcode := uop.opcode
    newMSHR.wdata := uop.wdata
    newMSHR.wmask := uop.wmask
    val raddr = Cat(uop.rtag, uop.index, uop.offset, 0.U(2.W))
    val waddr = Cat(uop.wtag, uop.index, 0.U(log2Up(CACHE_LINE_B).W))
    when(uop.opcode === CacheOpcode.LOAD) {
      // * just load to cache
      newMSHR.memReadAddr := raddr

      newMSHR.needReadMem := true.B

      newMSHR.axiReadDone := false.B
    }.elsewhen(uop.opcode === CacheOpcode.REPLACE) {
      // * replace cache line
      newMSHR.memReadAddr := raddr
      newMSHR.memWriteAddr := waddr

      newMSHR.needReadMem := true.B
      newMSHR.needReadCache := true.B
      newMSHR.needWriteMem := true.B

      newMSHR.axiReadDone := false.B
      newMSHR.axiWriteDone := false.B
    }.elsewhen(CacheOpcode.isUnCachedLoad(uop.opcode)) {
      // * uncached load
      newMSHR.uncached := true.B

      newMSHR.memReadAddr := raddr

      newMSHR.needReadMem := true.B

      newMSHR.axiReadDone := false.B
    }.elsewhen(CacheOpcode.isUnCachedStore(uop.opcode)) {
      // * uncached store
      newMSHR.uncached := true.B

      newMSHR.memWriteAddr := waddr
      newMSHR.needReadCache := true.B
      newMSHR.needWriteMem := true.B

      newMSHR.axiWriteDone := false.B
    }
  }

  for (i <- 0 until NUM_MSHR) {
    when(mshr(i).valid && mshr(i).axiReadDone && mshr(i).axiWriteDone) {
      mshr(i).valid := false.B
    }
  }

  // * AXI R/B id
  val axiRId = io.OUT_axi.r.bits.id
  val axiBId = io.OUT_axi.b.bits.id

  val wValidReg = RegInit(false.B)
  val wDataReg = RegInit(0.U(AXI_DATA_WIDTH.W))
  val wMaskReg = Reg(UInt(4.W))

  // * forward load data
  val memLoadFowardValidReg = RegInit(false.B)
  val memLoadFowardReg = Reg(new MemLoadFoward)
  memLoadFowardValidReg := io.OUT_axi.r.valid
  memLoadFowardReg.addr := mshr(axiRId).memReadAddr
  memLoadFowardReg.data := io.OUT_axi.r.bits.data
  memLoadFowardReg.uncached := mshr(axiRId).uncached

  io.OUT_memLoadFoward.valid := memLoadFowardValidReg
  io.OUT_memLoadFoward.bits := memLoadFowardReg

  // * cache interface
  // ** cache read
  // ** Select MSHR to read cache (W channel)
  val needReadCacheVec = mshr.map(e => (e.valid && e.needReadCache && !e.needWriteMem))
  val readCacheIndex = PriorityEncoder(needReadCacheVec)
  val hasNeedReadCache = needReadCacheVec.reduce(_ || _)

  // ** generate cache read request
  val dataReadRespValid = RegNext(io.OUT_DDataRead.valid)
  val dataReadRespMSHRIndex = RegNext(readCacheIndex)
  io.OUT_DDataRead.bits.addr := mshr(readCacheIndex).memWriteAddr
  io.OUT_DDataRead.bits.data := 0.U
  io.OUT_DDataRead.bits.way := 0.U
  io.OUT_DDataRead.bits.wmask := 0.U
  io.OUT_DDataRead.bits.write := false.B
  io.OUT_DDataRead.valid := hasNeedReadCache && !mshr(readCacheIndex).uncached

  when(io.OUT_axi.w.fire) {
    wValidReg := false.B
  }
  when(!wValidReg || io.OUT_axi.w.fire) {    
    when(mshr(dataReadRespMSHRIndex).valid) {    
      val wMSHR = mshr(dataReadRespMSHRIndex)  
      when(!wMSHR.uncached && dataReadRespValid) {
        wDataReg := io.IN_DDataResp.data(wMSHR.way).asUInt
        wMaskReg := Fill(CACHE_LINE_B, 1.U(1.W))
        wValidReg := wMSHR.needReadCache
        wMSHR.needReadCache := false.B
      }.elsewhen(wMSHR.uncached) {
        wDataReg := wMSHR.wdata
        wMaskReg := wMSHR.wmask
        wValidReg := wMSHR.needReadCache
        wMSHR.needReadCache := false.B
      }      
    }
  }
  // ** cache write
  io.OUT_DDataWrite := 0.U.asTypeOf(io.OUT_DDataWrite)
  when(io.OUT_axi.r.valid) {
    val rMSHRIndex = io.OUT_axi.r.bits.id
    val rMSHR = mshr(rMSHRIndex)
    val wdata = Wire(Vec(CACHE_LINE_B, UInt(8.W)))
    wdata := io.OUT_axi.r.bits.data.asTypeOf(wdata)
    val inCacheLineOffset = Cat(rMSHR.memReadAddr(log2Up(CACHE_LINE_B) - 1, 2), 0.U(2.W))
    for (i <- 0 until 4) {
      when(rMSHR.wmask(i)) {
        wdata(inCacheLineOffset + i.U) := rMSHR.wdata((i + 1) * 8 - 1, i * 8)
      }
    }
    io.OUT_DDataWrite.bits.addr := rMSHR.memReadAddr
    io.OUT_DDataWrite.bits.data := wdata.asUInt
    io.OUT_DDataWrite.bits.way := rMSHR.way
    io.OUT_DDataWrite.bits.wmask := Fill(CACHE_LINE_B, 1.U(1.W))
    io.OUT_DDataWrite.bits.write := true.B
    io.OUT_DDataWrite.valid := !CacheOpcode.isUnCached(rMSHR.opcode)
    rMSHR.axiReadDone := true.B
  }

  // * axi interface
  // ** ar
  val arValidReg = RegInit(false.B)
  val arAddrReg = RegInit(0.U(AXI_ADDR_WIDTH.W))
  val arIdReg = Reg(UInt(4.W))
  // ** Select MSHR to read Mem (AR channel)
  val arMSHRVec = mshr.map(e => (e.valid && e.needReadMem && e.axiWriteDone))
  val arMSHRIndex = PriorityEncoder(arMSHRVec)
  val hasArMSHR = arMSHRVec.reduce(_ || _)
  when(!arValidReg || io.OUT_axi.ar.fire) {    
    when(hasArMSHR) {
      val arMSHR = mshr(arMSHRIndex)
      arValidReg := arMSHR.needReadMem
      arAddrReg := arMSHR.memReadAddr
      arIdReg := arMSHRIndex
      arMSHR.needReadMem := false.B
    }.otherwise {
      arValidReg := false.B
    }
  }
  io.OUT_axi.ar.valid := arValidReg
  io.OUT_axi.ar.bits.addr := arAddrReg
  io.OUT_axi.ar.bits.len := 0.U
  io.OUT_axi.ar.bits.size := 2.U
  io.OUT_axi.ar.bits.burst := 1.U
  io.OUT_axi.ar.bits.id := arIdReg
  
  // ** aw
  val awValidReg = RegInit(false.B)
  val awAddrReg = RegInit(0.U(AXI_ADDR_WIDTH.W))
  val awIdReg = Reg(UInt(4.W))

  // ** Select MSHR to write Mem (AW channel)
  val awMSHRVec = mshr.map(e => (e.valid && e.needWriteMem))
  val awMSHRIndex = PriorityEncoder(awMSHRVec)
  val hasAwMSHR = awMSHRVec.reduce(_ || _)
  when(!awValidReg || io.OUT_axi.aw.fire) {
    when(hasAwMSHR) {
      val awMSHR = mshr(awMSHRIndex)
      awValidReg := awMSHR.needWriteMem
      awAddrReg := awMSHR.memWriteAddr
      awIdReg := awMSHRIndex
      awMSHR.needWriteMem := false.B
    }.otherwise {
      awValidReg := false.B
    }
  }
  io.OUT_axi.aw.valid := awValidReg
  io.OUT_axi.aw.bits.addr := awAddrReg
  io.OUT_axi.aw.bits.len := 0.U
  io.OUT_axi.aw.bits.size := 2.U
  io.OUT_axi.aw.bits.burst := 1.U
  io.OUT_axi.aw.bits.id := awIdReg

  // ** w
  io.OUT_axi.w.valid := wValidReg
  io.OUT_axi.w.bits.data := wDataReg
  io.OUT_axi.w.bits.strb := Fill(AXI_DATA_WIDTH / 8, 1.U(1.W))
  io.OUT_axi.w.bits.last := true.B

  // ** r
  io.OUT_axi.r.ready := true.B

  // ** b
  io.OUT_uncacheStoreResp := io.OUT_axi.b.fire
  io.OUT_axi.b.ready := true.B
  when(io.OUT_axi.b.fire) {
    mshr(io.OUT_axi.b.bits.id).axiWriteDone := true.B
  }
} 