import chisel3._
import chisel3.util._
import utils._
import scala.language.implicitConversions

class CacheControllerResp extends CoreBundle {
  val data = UInt(32.W)
}

class MemLoadFoward extends CoreBundle {
  val addr = UInt(XLEN.W)
  val data = UInt((AXI_DATA_WIDTH).W)
  val uncached = Bool()
}

class CacheControllerIO extends CoreBundle {
  // * Cache Controller Uop
  val IN_cacheCtrlUop = Flipped(Vec(3, Decoupled(new CacheCtrlUop)))

  // * Cache Array Management
  // ** Data Cache Tag & Data
  val OUT_DDataRead = Valid(new DDataReq)
  val OUT_DDataWrite = Valid(new DDataReq)
  val IN_DDataResp = Flipped(new DDataResp)
  // ** Inst Cache Data
  val OUT_IDataWrite = Valid(new IDataWrite)

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
  val cacheId = UInt(CACHE_ID_LEN.W)
  val opcode = UInt(OpcodeWidth.W)
  val way = UInt(log2Up(DCACHE_WAYS).W)
  // * read from Mem
  val memReadAddr = UInt(XLEN.W)
  val needReadMem = Bool()
  // * write to Mem
  val memWriteAddr = UInt(XLEN.W)
  val needReadCache = Bool() // * W channel ! Means Cache for uncachedb / load write data for uncached
  val needWriteMem = Bool() // * AW
  
  val axiReadDone = Bool()
  val axiWriteDone = Bool()

  // * data
  val wdata = UInt(XLEN.W)
  val wmask = UInt(4.W)

  def loadAddrInFlight(cacheId: UInt, addr: UInt) = {
    valid && this.cacheId === cacheId &&
    memReadAddr(XLEN - 1, log2Up(CACHE_LINE_B)) === addr(XLEN - 1, log2Up(CACHE_LINE_B))
    //* when axiReadDone is pulled down, the data is written to cache, how ever, the read op was performed
    //* one cycle before, so the data may not be correct
    //* !axiReadDone
  }

  def cacheLocation() = {
    Cat(way, memReadAddr(log2Up(CACHE_LINE_B) + log2Up(DCACHE_SETS) - 1, log2Up(CACHE_LINE_B)))// !
  }

  def AXISize() = {
    MuxLookup(opcode, log2Ceil(AXI_DATA_WIDTH / 8).U)(Seq(
      CacheOpcode.UNCACHED_LW -> log2Ceil(4).U,
      CacheOpcode.UNCACHED_LH -> log2Ceil(2).U,
      CacheOpcode.UNCACHED_LB -> log2Ceil(1).U,
      CacheOpcode.UNCACHED_SW -> log2Ceil(4).U,
      CacheOpcode.UNCACHED_SH -> log2Ceil(2).U,
      CacheOpcode.UNCACHED_SB -> log2Ceil(1).U
    ))
  }

  def uncachedAXIwstrb() = {
    val wstrb = Wire(UInt((AXI_DATA_WIDTH / 8).W))
    if(AXI_DATA_WIDTH == XLEN) {
      wstrb := wmask       
    } else {
      val axiOffsetBits = log2Up(AXI_DATA_WIDTH / 8)
      val addrOffset = Cat(memWriteAddr(axiOffsetBits - 1, log2Up(XLEN / 8)), 0.U(log2Up(XLEN / 8).W))      
      wstrb := (wmask << addrOffset)
    }
    wstrb
  }

  def uncachedAXIwdata() = {
    val axiwdata = Wire(UInt(AXI_DATA_WIDTH.W))
    if(AXI_DATA_WIDTH == XLEN) {
      axiwdata := this.wdata
    } else {
      val axiOffsetBits = log2Up(AXI_DATA_WIDTH / 8)
      val addrOffset = memWriteAddr(axiOffsetBits - 1, 0)
      axiwdata := (wdata << (addrOffset * 8.U))
    }
    axiwdata
  }
  
  def axiARaddr() = {
    val axiOffsetBits = log2Up(AXI_DATA_WIDTH / 8)
    val araddr = Wire(UInt(AXI_ADDR_WIDTH.W))
    araddr := Cat(memReadAddr(XLEN - 1,  axiOffsetBits), 
                  Mux(uncached, 
                    memReadAddr(axiOffsetBits - 1, 0),
                    0.U(log2Up(AXI_DATA_WIDTH / 8).W)
                  )
    )
    araddr
  }

  def axiAWaddr() = {
    val axiOffsetBits = log2Up(AXI_DATA_WIDTH / 8)
    val awaddr = Wire(UInt(AXI_ADDR_WIDTH.W))
    awaddr := Cat(memWriteAddr(XLEN - 1,  axiOffsetBits), 
                  Mux(uncached, 
                    memWriteAddr(axiOffsetBits - 1, 0),
                    0.U(log2Up(AXI_DATA_WIDTH / 8).W)
                  )
    )
    awaddr
  }
}

/* 
  def isLoadAddrAlreadyInFlight(addr: UInt) = {
    io.IN_mshrs.map(e => e.loadAddrInFlight(addr)).reduce(_ || _) ||
    (io.OUT_cacheCtrlUop.valid && io.OUT_cacheCtrlUop.bits.loadAddrAlreadyInFlight(addr))
  }

  def isMSHRConflict(uop: CacheCtrlUop) = {
    val conflict = WireInit(false.B)
    for(i <- 0 until NUM_MSHR) {
      when (io.IN_mshrs(i).valid) {
        // * Read after write: read must be processed after write to mem. Note that mshr(i)/uop can be different cache line
        when(io.IN_mshrs(i).memWriteAddr(XLEN - 1, log2Up(CACHE_LINE_B)) === uop.readAddr()(XLEN - 1, log2Up(CACHE_LINE_B))) {
          conflict := true.B
        }
        // * the cache line has another inflight operation
        when(io.IN_mshrs(i).cacheLocation() === uop.cacheLocation()) {
          conflict := true.B
        }
      }
    }
    
    when(io.OUT_cacheCtrlUop.valid) {
      // * RAW
      when(io.OUT_cacheCtrlUop.bits.writeAddr()(XLEN - 1, log2Up(CACHE_LINE_B)) === uop.readAddr()(XLEN - 1, log2Up(CACHE_LINE_B))) {
        conflict := true.B
      }
      // * same cache lien
      when(io.OUT_cacheCtrlUop.bits.cacheLocation() === uop.cacheLocation()) {
        conflict := true.B
      }
    }

    conflict
  }
 */
object MSHRChecker extends HasCoreParameters {

  def isLoadAddrAlreadyInFlight(mshr: Vec[MSHR], OUT_uop: DecoupledIO[CacheCtrlUop], cacheId: UInt, addr: UInt) = {
    mshr.map(_.loadAddrInFlight(cacheId, addr)).reduce(_ || _) ||
    (OUT_uop.valid && OUT_uop.bits.loadAddrAlreadyInFlight(cacheId, addr))
  }

  def conflict(mshr: Vec[MSHR], OUT_uop: DecoupledIO[CacheCtrlUop], uop: CacheCtrlUop) = {
    val conflict = WireInit(false.B)
    val mshrConflict = Wire(Vec(NUM_MSHR, Bool()))    
    dontTouch(mshrConflict)
    for (i <- 0 until NUM_MSHR) {
      mshrConflict(i) := false.B
      when(mshr(i).valid && mshr(i).cacheId === uop.cacheId) {
        // ! TODO: check if mshr/uop really read/write the memReadAddr/memWriteAddr
        // * Read after write: read must be processed after write to mem. Note that mshr(i)/uop can be different cache line
        when(mshr(i).memWriteAddr(XLEN - 1, log2Up(CACHE_LINE_B)) === uop.readAddr()(XLEN - 1, log2Up(CACHE_LINE_B))) {
          mshrConflict(i) := true.B
        }
        when(mshr(i).memReadAddr(XLEN - 1, log2Up(CACHE_LINE_B)) === uop.writeAddr()(XLEN - 1, log2Up(CACHE_LINE_B))) {
          mshrConflict(i) := true.B
        }
        // * the cache line has another inflight operation
        when(mshr(i).cacheLocation() === uop.cacheLocation()) {
          mshrConflict(i) := true.B
        }
      }
    }
    conflict := mshrConflict.reduce(_ || _)
    when(OUT_uop.valid && OUT_uop.bits.cacheId === uop.cacheId) {
      // * RAW
      when(OUT_uop.bits.writeAddr()(XLEN - 1, log2Up(CACHE_LINE_B)) === uop.readAddr()(XLEN - 1, log2Up(CACHE_LINE_B))) {
        conflict := true.B
      }
      when(OUT_uop.bits.readAddr()(XLEN - 1, log2Up(CACHE_LINE_B)) === uop.writeAddr()(XLEN - 1, log2Up(CACHE_LINE_B))) {
        conflict := true.B
      }
      // * same cache line
      when(OUT_uop.bits.cacheLocation() === uop.cacheLocation()) {
        conflict := true.B
      }
    }
    conflict
  }
}

class CacheController extends CoreModule {
  val io = IO(new CacheControllerIO)

  val uopValidIndex = PriorityEncoder(io.IN_cacheCtrlUop.map(_.valid))
  val hasUopValid = io.IN_cacheCtrlUop.map(_.valid).reduce(_ || _)

  val uop = io.IN_cacheCtrlUop(uopValidIndex).bits
  val uopValid = io.IN_cacheCtrlUop(uopValidIndex).valid

  val mshr = RegInit(VecInit(Seq.fill(NUM_MSHR)(0.U.asTypeOf(new MSHR))))
  val mshrFree = mshr.map(!_.valid)
  val mshrFreeIndex = PriorityEncoder(mshrFree)
  val canAllocateMSHR = mshrFree.reduce(_ || _)

  for (i <- 0 until 3) {
    io.IN_cacheCtrlUop(i).ready := false.B
  }
  when(hasUopValid) {
    io.IN_cacheCtrlUop(uopValidIndex).ready := canAllocateMSHR
  }
  
  io.OUT_MSHR := mshr

  when(canAllocateMSHR && uopValid) {
    val newMSHR = Wire(new MSHR)
    mshr(mshrFreeIndex) := newMSHR

    newMSHR.valid := true.B
    newMSHR.uncached := false.B
    newMSHR.cacheId := uop.cacheId
    newMSHR.opcode := uop.opcode
    newMSHR.way := uop.way

    newMSHR.memReadAddr := 0.U
    newMSHR.needReadMem := false.B

    newMSHR.memWriteAddr := 0.U
    newMSHR.needReadCache := false.B
    newMSHR.needWriteMem := false.B

    newMSHR.axiReadDone := true.B
    newMSHR.axiWriteDone := true.B
    
    newMSHR.wdata := uop.wdata
    newMSHR.wmask := uop.wmask
    val raddr = Cat(uop.rtag, uop.index, uop.offset)
    val waddr = Cat(uop.wtag, uop.index, uop.offset)
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
    }.elsewhen(uop.opcode === CacheOpcode.INVALIDATE) {
      // * invalidate cache line
      newMSHR.memWriteAddr := waddr

      newMSHR.needReadCache := true.B
      newMSHR.needWriteMem := true.B

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
  val wStrbReg = Reg(UInt((AXI_DATA_WIDTH / 8).W))

  // * forward load data
  val memLoadFowardValidReg = RegInit(false.B)
  val memLoadFowardReg = Reg(new MemLoadFoward)
  memLoadFowardValidReg := io.OUT_axi.r.valid
  memLoadFowardReg.addr := mshr(axiRId).memReadAddr
  memLoadFowardReg.data := io.OUT_axi.r.bits.data
  memLoadFowardReg.uncached := mshr(axiRId).uncached

  io.OUT_memLoadFoward.valid := RegNext(memLoadFowardValidReg)
  io.OUT_memLoadFoward.bits := RegNext(memLoadFowardReg)

  // * cache interface
  // ** cache read
  // ** Select MSHR to read cache (W channel)
  val wLockValid = RegInit(false.B)
  val wLockIndex = Reg(UInt(4.W))

  // val needReadCacheVec = mshr.map(e => (e.valid && e.needReadCache && !e.needWriteMem))
  // val readCacheIndex = PriorityEncoder(needReadCacheVec)
  // val hasNeedReadCache = needReadCacheVec.reduce(_ || _)
  val readCacheIndex = wLockIndex
  val hasNeedReadCache = wLockValid && mshr(wLockIndex).needReadCache


  // ** generate cache read request
  // ** Latch 1 cycle, cache controller must be able to read DCache every cycle
  val dataReadRespValid = RegNext(hasNeedReadCache)
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
    when(dataReadRespValid) {    
      val wMSHR = mshr(dataReadRespMSHRIndex)  
      when(!wMSHR.uncached) {
        wDataReg := io.IN_DDataResp.data(wMSHR.way).asUInt
        wStrbReg := Fill(CACHE_LINE_B, 1.U(1.W))
        wValidReg := wMSHR.needReadCache
        wLockValid := false.B // ! Order matter, see below assignment of wLockValid
        wMSHR.needReadCache := false.B
      }.elsewhen(wMSHR.uncached) {
        wDataReg := wMSHR.uncachedAXIwdata()
        wStrbReg := wMSHR.uncachedAXIwstrb()
        wValidReg := wMSHR.needReadCache
        wLockValid := false.B // ! Order matter, see below assignment of wLockValid
        wMSHR.needReadCache := false.B
      }      
    }
  }
  // ** cache write
  io.OUT_DDataWrite := 0.U.asTypeOf(io.OUT_DDataWrite)
  io.OUT_IDataWrite := 0.U.asTypeOf(io.OUT_IDataWrite)
  when(io.OUT_axi.r.valid) {
    val rMSHRIndex = io.OUT_axi.r.bits.id
    val rMSHR = mshr(rMSHRIndex)
    val wdata = Wire(Vec(CACHE_LINE_B, UInt(8.W)))
    wdata := io.OUT_axi.r.bits.data.asTypeOf(wdata)
    // val inCacheLineOffset = Cat(rMSHR.memReadAddr(log2Up(CACHE_LINE_B) - 1, 2), 0.U(2.W))
    // for (i <- 0 until 4) {
    //   when(rMSHR.wmask(i)) {
    //     wdata(inCacheLineOffset + i.U) := rMSHR.wdata((i + 1) * 8 - 1, i * 8)
    //   }
    // }
    when(rMSHR.cacheId === CacheId.DCACHE) {       
      io.OUT_DDataWrite.bits.addr := rMSHR.memReadAddr
      io.OUT_DDataWrite.bits.data := wdata.asUInt
      io.OUT_DDataWrite.bits.way := rMSHR.way
      io.OUT_DDataWrite.bits.wmask := Fill(CACHE_LINE_B, 1.U(1.W))
      io.OUT_DDataWrite.bits.write := true.B
      io.OUT_DDataWrite.valid := !CacheOpcode.isUnCached(rMSHR.opcode)
    }.elsewhen(rMSHR.cacheId === CacheId.ICACHE) {
      io.OUT_IDataWrite.bits.addr := rMSHR.memReadAddr
      io.OUT_IDataWrite.bits.data := wdata.asTypeOf(io.OUT_IDataWrite.bits.data)
      io.OUT_IDataWrite.bits.way := rMSHR.way
      io.OUT_IDataWrite.bits.wmask := Fill(CACHE_LINE_B, 1.U(1.W))
      io.OUT_IDataWrite.valid := true.B
    }
    rMSHR.axiReadDone := true.B
  }

  // * axi interface
  // ** ar
  val arValidReg = RegInit(false.B)
  val arAddrReg = RegInit(0.U(AXI_ADDR_WIDTH.W))
  val arIdReg = Reg(UInt(4.W))
  val arSizeReg = Reg(UInt(3.W))
  // ** Select MSHR to read Mem (AR channel)
  val arMSHRVec = mshr.map(e => (e.valid && e.needReadMem && e.axiWriteDone))
  val arMSHRIndex = PriorityEncoder(arMSHRVec)
  val hasArMSHR = arMSHRVec.reduce(_ || _)
  when(!arValidReg || io.OUT_axi.ar.fire) {    
    when(hasArMSHR) {
      val arMSHR = mshr(arMSHRIndex)
      arValidReg := arMSHR.needReadMem
      arAddrReg := arMSHR.axiARaddr()
      arIdReg := arMSHRIndex
      arSizeReg := mshr(arMSHRIndex).AXISize()
      arMSHR.needReadMem := false.B
    }.otherwise {
      arValidReg := false.B
    }
  }
  io.OUT_axi.ar.valid := arValidReg
  io.OUT_axi.ar.bits.addr := arAddrReg
  io.OUT_axi.ar.bits.len := 0.U
  io.OUT_axi.ar.bits.size := arSizeReg
  io.OUT_axi.ar.bits.burst := 1.U
  io.OUT_axi.ar.bits.id := arIdReg
  
  // ** aw
  val awValidReg = RegInit(false.B)
  val awAddrReg = RegInit(0.U(AXI_ADDR_WIDTH.W))
  val awIdReg = Reg(UInt(4.W))
  val awSizeReg = Reg(UInt(3.W))

  // ** Select MSHR to write Mem (AW channel)
  val awMSHRVec = mshr.map(e => (e.valid && e.needWriteMem))
  val awMSHRIndex = PriorityEncoder(awMSHRVec)
  val hasAwMSHR = awMSHRVec.reduce(_ || _) && !wLockValid


  when(!awValidReg || io.OUT_axi.aw.fire) {
    when(hasAwMSHR) {
      val awMSHR = mshr(awMSHRIndex)
      awValidReg := awMSHR.needWriteMem
      awAddrReg := awMSHR.axiAWaddr()
      awIdReg := awMSHRIndex
      awSizeReg := mshr(awMSHRIndex).AXISize()
      awMSHR.needWriteMem := false.B
      // !
      wLockValid := true.B
      wLockIndex := awMSHRIndex
      // !
    }.otherwise {
      awValidReg := false.B
    }
  }
  io.OUT_axi.aw.valid := awValidReg
  io.OUT_axi.aw.bits.addr := awAddrReg
  io.OUT_axi.aw.bits.len := 0.U
  io.OUT_axi.aw.bits.size := awSizeReg
  io.OUT_axi.aw.bits.burst := 1.U
  io.OUT_axi.aw.bits.id := awIdReg

  // ** w
  io.OUT_axi.w.valid := wValidReg
  io.OUT_axi.w.bits.data := wDataReg
  io.OUT_axi.w.bits.strb := wStrbReg
  io.OUT_axi.w.bits.last := true.B

  // ** r
  io.OUT_axi.r.ready := true.B

  // ** b
  io.OUT_uncacheStoreResp := (io.OUT_axi.b.fire) && mshr(axiBId).uncached
  io.OUT_axi.b.ready := true.B
  when(io.OUT_axi.b.fire) {
    mshr(io.OUT_axi.b.bits.id).axiWriteDone := true.B
  }
} 