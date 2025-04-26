import chisel3._
import chisel3.util._
import utils._
import os.stat
import dataclass.data

trait HasLSUOps {
  def U    = 0.U(1.W)
  def S    = 1.U(1.W)
  def BYTE = 0.U(2.W)
  def HALF = 1.U(2.W)
  def WORD = 2.U(2.W)
  def R    = 0.U(1.W)
  def W    = 1.U(1.W)

  def LB  = BitPat("b0000")
  def LBU = BitPat("b0001")

  def LH  = BitPat("b0010")
  def LHU = BitPat("b0011")

  def LW = BitPat("b0100")

  def SB = BitPat("b1000")

  def SH = BitPat("b1010")

  def SW = BitPat("b1100")
}

class LSUIO extends CoreBundle {
  val IN_AGUUop = Flipped(Decoupled(new AGUUop))
  val OUT_writebackUop = Valid(new WritebackUop)
  val master = new AXI4(32, 32)
}

class LSU extends CoreModule with HasLSUOps {
  val io = IO(new LSUIO)

  val amoALU = Module(new AMOALU)

  val sIdle :: sWaitResp :: sWaitAmoSave :: Nil = Enum(3)
  val state = RegInit(sIdle)

  val respValid = Wire(Bool())
  val insert1 = (state === sIdle && io.IN_AGUUop.valid)
  val insert2 = (state === sWaitResp && respValid)
  val insert = insert1 || insert2
  val inUop = RegEnable(io.IN_AGUUop.bits, insert1)
  val opcode = inUop.opcode
  val isLr = inUop.fuType === FuType.AMO && opcode === AMOOp.LR_W
  val isSc = inUop.fuType === FuType.AMO && opcode === AMOOp.SC_W

  // * reservation station
  val reservation = Reg(UInt(XLEN.W))
  val reservationValid = RegInit(false.B)
  val scFail = inUop.addr =/= reservation || !reservationValid
  respValid := (io.master.r.fire || io.master.b.fire) || (isSc && scFail)
  
  io.IN_AGUUop.ready := state === sIdle

  state := MuxLookup(state, sIdle)(
    Seq(
      sIdle -> Mux(io.IN_AGUUop.valid, sWaitResp, sIdle),
      sWaitResp -> Mux(respValid, 
      Mux(inUop.fuType === FuType.LSU || isLr || isSc, 
        sIdle, sWaitAmoSave), sWaitResp),
      sWaitAmoSave -> Mux(io.master.b.fire, sIdle, sWaitAmoSave)
    )
  )
  
  val uopRead  = state === sWaitResp && ((inUop.fuType === FuType.LSU && opcode(3) === R) || (inUop.fuType === FuType.AMO && opcode =/= AMOOp.SC_W))
  val uopWrite = (state === sWaitResp && ((inUop.fuType === FuType.LSU && opcode(3) === W) || (isSc && !scFail))) || 
                 (state === sWaitAmoSave && inUop.fuType === FuType.AMO)

  val memLen     = Mux(inUop.fuType === FuType.LSU, opcode(2, 1), 2.U)
  val loadU      = opcode(0)

  val addr        = inUop.addr
  val addr_offset = addr(1, 0)

  // ar_valid/aw_valid/w_valid 当一个valid请求进入时置为true,在相应通道握手后为false
  val ar_valid = RegInit(false.B)
  ar_valid := Mux(
    insert,
    true.B,
    Mux(io.master.ar.fire, false.B, ar_valid)
  )
  io.master.ar.valid      := ar_valid && uopRead
  io.master.ar.bits.addr  := addr
  io.master.ar.bits.id    := 0.U
  io.master.ar.bits.len   := 0.U
  io.master.ar.bits.size  := memLen
  io.master.ar.bits.burst := "b01".U

  val rdata = io.master.r.bits.data
  val rdataReg = RegEnable(rdata, io.master.r.fire)
  io.master.r.ready := true.B

  amoALU.io.IN_src1 := rdataReg
  amoALU.io.IN_src2 := inUop.wdata
  amoALU.io.IN_opcode := opcode

  val aw_valid = RegInit(false.B)
  aw_valid := Mux(
    insert,
    true.B,
    Mux(io.master.aw.fire, false.B, aw_valid)
  )
  io.master.aw.valid      := aw_valid && uopWrite
  io.master.aw.bits.addr  := addr
  io.master.aw.bits.id    := 0.U
  io.master.aw.bits.len   := 0.U
  io.master.aw.bits.size  := memLen
  io.master.aw.bits.burst := "b01".U

  val w_valid = RegInit(false.B)
  w_valid := Mux(
    insert,
    true.B,
    Mux(io.master.w.fire, false.B, w_valid)
  )
  val wData = Mux(
    state === sWaitResp,
    inUop.wdata << (addr_offset << 3.U),
    amoALU.io.OUT_res
  )
  io.master.w.valid     := w_valid && uopWrite
  io.master.w.bits.data := wData
  io.master.w.bits.strb := MuxLookup(memLen, 0.U(4.W))(
    Seq(
      0.U(2.W) -> "b0001".U,
      1.U(2.W) -> "b0011".U,
      2.U(2.W) -> "b1111".U
    )
  ) << addr_offset
  io.master.w.bits.last := true.B

  io.master.b.ready := true.B

  val raw_data      = rdata >> (addr_offset << 3.U)
  val sign_ext_data = WireDefault(raw_data)
  when(memLen === BYTE) {
    sign_ext_data := Cat(Fill(24, ~loadU & raw_data(7)), raw_data(7, 0))
  }.elsewhen(memLen === HALF) {
    sign_ext_data := Cat(Fill(16, ~loadU & raw_data(15)), raw_data(15, 0))
  }

  val uop = Reg(new WritebackUop)
  val uopValid = RegInit(false.B)
  
  uopValid := state === sWaitResp && respValid
  
  uop.data := Mux(isSc, scFail, sign_ext_data)
  uop.prd := inUop.prd
  uop.robPtr := inUop.robPtr
  uop.flag := 0.U
  uop.target := 0.U
  uop.dest := inUop.dest
  when(uopValid) {
    when(isLr) {
      reservation := addr
      reservationValid := true.B
    }.elsewhen(isSc) {
      reservationValid := false.B
    }
  }

  io.OUT_writebackUop.bits := uop
  io.OUT_writebackUop.valid := uopValid
}

class AMOALUIO extends CoreBundle {
  val IN_src1 = Flipped(UInt(XLEN.W))
  val IN_src2 = Flipped(UInt(XLEN.W))
  val IN_opcode = Flipped(UInt(OpcodeWidth.W))
  val OUT_res = UInt(XLEN.W)
}

class AMOALU extends CoreModule {
  val io = IO(new AMOALUIO)

  val src1 = io.IN_src1
  val src2 = io.IN_src2
  val opcode = io.IN_opcode

  val res = Wire(UInt(XLEN.W))
  res := 0.U

  res := MuxLookup(opcode, src2)(
    Seq(
      AMOOp.SWAP_W -> src2,
      AMOOp.ADD_W  -> (src1 + src2),
      AMOOp.AND_W  -> (src1 & src2),
      AMOOp.OR_W   -> (src1 | src2),
      AMOOp.XOR_W  -> (src1 ^ src2),
      AMOOp.MIN_W  -> Mux(src1.asSInt < src2.asSInt, src1, src2),
      AMOOp.MAX_W  -> Mux(src1.asSInt > src2.asSInt, src1, src2),
      AMOOp.MINU_W -> Mux(src1 < src2, src1, src2),
      AMOOp.MAXU_W -> Mux(src1 > src2, src1, src2)      
    )
  )

  io.OUT_res := res
}

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

class StoreBypassReq extends CoreBundle {
  val addr = UInt(XLEN.W)
  val stqPtr = RingBufferPtr(STQ_SIZE)
}

class StoreBypassResp extends CoreBundle {
  val data = UInt(XLEN.W)
  val mask = UInt(4.W)
}

class NewLSUIO extends CoreBundle {
  val IN_loadUop = Flipped(Decoupled(new AGUUop))
  val OUT_loadNegAck = Valid(new LoadNegAck)
  val IN_storeUop = Flipped(Decoupled(new AGUUop))
  val OUT_storeAck = Valid(new StoreAck)
  val IN_amoUop = Flipped(Decoupled(new AGUUop))
  val OUT_amoAck = Valid(new StoreAck)
  // * Store Queue Bypass
  val OUT_storeBypassReq = new StoreBypassReq
  val IN_storeBypassResp = Flipped(new StoreBypassResp)
  // * Store Buffer Bypass
  val IN_storeBufferBypassResp = Flipped(new StoreBypassResp)
  // * DCache Interface
  val OUT_tagReq = Decoupled(new DTagReq)
  val IN_tagResp = Flipped(new DTagResp)
  val OUT_dataReq = Decoupled(new DDataReq)
  val IN_dataResp = Flipped(new DDataResp)
  // * Internal MMIO Interface (CLINT)
  val OUT_mmioReq = new MMIOReq
  val IN_mmioResp = Flipped(new MMIOResp)
  // * Cache Controller Interface
  val OUT_cacheCtrlUop = Decoupled(new CacheCtrlUop)
  val OUT_uncacheUop = Decoupled(new CacheCtrlUop)
  val IN_mshrs = Flipped(Vec(NUM_MSHR, new MSHR))
  val IN_memLoadFoward = Flipped(Valid(new MemLoadFoward))
  val IN_uncacheStoreResp = Flipped(Bool())

  val OUT_writebackUop = Valid(new WritebackUop)

  val IN_flush = Flipped(Bool())
}

class NewLSU extends CoreModule with HasLSUOps {
  val io = IO(new NewLSUIO)

  // * dummy counter for replacement
  val replaceCounter = RegInit(0.U(2.W))
  replaceCounter := Mux(replaceCounter === DCACHE_WAYS.U - 1.U, 0.U, replaceCounter + 1.U)

  // * Submodules
  val loadResultBuffer = Module(new LoadResultBuffer(8))
  val uncachedLSU = Module(new UncachedLSU)
  val amoALU = Module(new AMOALU)

  io.OUT_cacheCtrlUop.valid := false.B
  io.OUT_cacheCtrlUop.bits := 0.U.asTypeOf(new CacheCtrlUop)
  io.OUT_dataReq.valid := false.B
  io.OUT_dataReq.bits := 0.U.asTypeOf(new DDataReq)
  io.OUT_tagReq.valid := false.B
  io.OUT_tagReq.bits := 0.U.asTypeOf(new DTagReq)

  // * DCache Flush State Machine
  val sFlushIdle :: sFlushActive :: Nil = Enum(2)
  val flushState = RegInit(sFlushActive)
  val flushIndex = RegInit(0.U(log2Up(DCACHE_SETS).W))
  val flushWay = RegInit(0.U(log2Up(DCACHE_WAYS).W))
  switch(sFlushActive) {
    is(sFlushIdle) {
      flushState := sFlushIdle
    }
    is(sFlushActive) {
      when(flushIndex === (DCACHE_SETS - 1).U) {        
        when(flushWay === (DCACHE_WAYS - 1).U) {
          flushState := sFlushIdle
        }.otherwise {
          flushIndex := 0.U
          flushWay := flushWay + 1.U
        }
      }.otherwise {
        flushIndex := flushIndex + 1.U
      }
    }
  }

  // * write tag
  val writeTag = WireInit(false.B)
  val storeWriteData = WireInit(false.B)

  // * Load Pipeline
  val loadStage = Reg(Vec(2, new AGUUop))
  val loadStageValid = RegInit(VecInit(Seq.fill(2)(false.B)))

  // * Store Pipeline
  val storeStage = Reg(Vec(2, new AGUUop))
  val storeStageValid = RegInit(VecInit(Seq.fill(2)(false.B)))

  // * Amo State Machine
  val sAmoIdle :: sAmoLoad :: sAmoALU :: sAmoStore :: sAmoWriteback :: Nil = Enum(5)
  val amoState = RegInit(sAmoIdle)
  val amoUopReg = Reg(new AGUUop)

  // * Reservation 
  val reservation = Reg(UInt(XLEN.W))
  val reservationValid = RegInit(false.B)

  // * Cache Control
  val cacheCtrlUop = Reg(new CacheCtrlUop)
  val cacheCtrlUopValid = RegInit(false.B)  

  val cacheUopNext = WireInit(0.U.asTypeOf(new CacheCtrlUop))
  cacheUopNext.cacheId := CacheId.DCACHE
  val cacheUopValidNext = WireInit(false.B)
  
  io.OUT_cacheCtrlUop.valid := cacheCtrlUopValid
  io.OUT_cacheCtrlUop.bits := cacheCtrlUop
  when(io.OUT_cacheCtrlUop.fire) {  
    cacheCtrlUopValid := false.B
  }

  def isLoadAddrAlreadyInFlight(addr: UInt) = {
    MSHRChecker.isLoadAddrAlreadyInFlight(io.IN_mshrs, io.OUT_cacheCtrlUop, CacheId.DCACHE, addr)
  }

  def isMSHRConflict(uop: CacheCtrlUop) = {
    MSHRChecker.conflict(io.IN_mshrs, io.OUT_cacheCtrlUop, uop)
  }

  val idle = !writeTag && io.OUT_dataReq.ready && io.OUT_tagReq.ready && !storeWriteData && amoState === sAmoIdle && flushState === sFlushIdle
  // ** For now, we only support one load/store at a time
  val serveAmo = idle && io.IN_amoUop.valid
  val serveLoad = idle && (!Addr.isUncached(io.IN_loadUop.bits.addr) || uncachedLSU.io.IN_loadUop.ready) && !io.IN_amoUop.valid
  val serveStore = idle && (!Addr.isUncached(io.IN_storeUop.bits.addr) || uncachedLSU.io.IN_storeUop.ready) && !io.IN_amoUop.valid && !io.IN_loadUop.valid 

  io.IN_amoUop.ready := serveAmo
  io.IN_loadUop.ready := serveLoad
  io.IN_storeUop.ready := serveStore

  val loadNegAck = Reg(new LoadNegAck)
  val loadNegAckValid = RegInit(false.B)
  val storeAck = Reg(new StoreAck)
  val storeAckValid = RegInit(false.B)
  val amoAck = Reg(new StoreAck)
  val amoAckValid = RegInit(false.B)

  loadNegAckValid := false.B
  storeAckValid := false.B
  amoAckValid := false.B

  val tagResp = Wire(Vec(DCACHE_WAYS, new DTag))
  tagResp := io.IN_tagResp.tags
  val dataResp = WireInit(io.IN_dataResp.data)

  // ** Load/Store Stage 0
  val inLoadUop = io.IN_loadUop.bits
  val loadIsUncached = Addr.isUncached(inLoadUop.addr)
  val loadIsInternalMMIO = Addr.isInternalMMIO(inLoadUop.addr)
  val loadUop = io.IN_loadUop.valid && serveLoad && (!loadIsUncached || loadIsInternalMMIO)
  loadStage(0) := inLoadUop
  loadStageValid(0) := loadUop && (!io.IN_flush || inLoadUop.dest === Dest.PTW)

  val inStoreUop = io.IN_storeUop.bits
  val storeIsUncached = Addr.isUncached(inStoreUop.addr)
  val storeIsInternalMMIO = Addr.isInternalMMIO(inStoreUop.addr)
  val storeUop = io.IN_storeUop.valid && serveStore
  storeStage(0) := inStoreUop
  storeStageValid(0) := storeUop

  // * Uncache Load/Store
  uncachedLSU.io.IN_loadUop.valid := io.IN_loadUop.valid && loadIsUncached && !loadIsInternalMMIO && serveLoad
  uncachedLSU.io.IN_loadUop.bits := io.IN_loadUop.bits
  uncachedLSU.io.IN_storeUop.valid := io.IN_storeUop.valid && storeIsUncached && !storeIsInternalMMIO && serveStore
  uncachedLSU.io.IN_storeUop.bits := io.IN_storeUop.bits
  uncachedLSU.io.IN_memLoadFoward := io.IN_memLoadFoward
  uncachedLSU.io.IN_uncacheStoreResp := io.IN_uncacheStoreResp
  uncachedLSU.io.IN_flush := io.IN_flush

  uncachedLSU.io.OUT_cacheCtrlUop.ready := false.B
  uncachedLSU.io.OUT_loadUop.ready := false.B

  io.OUT_uncacheUop <> uncachedLSU.io.OUT_cacheCtrlUop

  // * Query Store Queue Bypass, resp available in next cycle (loadStage(0))
  io.OUT_storeBypassReq.addr := io.IN_loadUop.bits.addr
  io.OUT_storeBypassReq.stqPtr := io.IN_loadUop.bits.stqPtr

  io.OUT_mmioReq := 0.U.asTypeOf(new MMIOReq)

  // * Amo 
  val inAmoUop = io.IN_amoUop.bits
  val inAmoIsLr = inAmoUop.opcode === AMOOp.LR_W
  val inAmoIsSc = inAmoUop.opcode === AMOOp.SC_W
  val amoUop = io.IN_amoUop.valid && serveAmo
  amoUopReg := inAmoUop

  when(loadUop) {
    when(loadIsInternalMMIO) {
      io.OUT_mmioReq.ren := true.B
      io.OUT_mmioReq.addr := inLoadUop.addr
    }.otherwise {
      // * Tag Request
      io.OUT_tagReq.valid := true.B
      io.OUT_tagReq.bits.addr := inLoadUop.addr
      // * Data Request
      io.OUT_dataReq.valid := true.B
      io.OUT_dataReq.bits.addr := inLoadUop.addr
    }
  }.elsewhen(storeUop) {
    when(storeIsInternalMMIO) {
      io.OUT_mmioReq.wen := true.B
      io.OUT_mmioReq.addr := inStoreUop.addr
      io.OUT_mmioReq.wdata := inStoreUop.wdata
    }.otherwise {
      // * Tag Request
      io.OUT_tagReq.valid := true.B
      io.OUT_tagReq.bits.addr := inStoreUop.addr
    }
  }.elsewhen(amoUop) { // amoState === sAmoIdle && amoUop
    // * Tag
    io.OUT_tagReq.valid := true.B
    io.OUT_tagReq.bits.addr := inAmoUop.addr
    // * Data
    io.OUT_dataReq.valid := true.B
    io.OUT_dataReq.bits.addr := inAmoUop.addr
  }

  val loadNeedCacheUop = WireInit(false.B)
  val storeNeedCacheUop = WireInit(false.B)
  val amoNeedCacheUop = WireInit(false.B)
  dontTouch(loadNeedCacheUop)
  dontTouch(storeNeedCacheUop)
  dontTouch(amoNeedCacheUop)

  // ** Load/Store Stage 1
  
  // * Load Result
  val loadResult = WireInit(0.U.asTypeOf(new LoadResult))
  val loadResultValid = WireInit(false.B)
  loadResultBuffer.io.IN_loadResult.valid := loadResultValid
  loadResultBuffer.io.IN_loadResult.bits := loadResult
  loadResultBuffer.io.IN_memLoadFoward := io.IN_memLoadFoward
  loadResultBuffer.io.IN_flush := io.IN_flush
  
  // * Load cache hit or miss 
  val loadTag = loadStage(0).addr(XLEN - 1, XLEN - 1 - DCACHE_TAG + 1)
  val loadTagHitOH = tagResp.map(e => e.valid && e.tag === loadTag)
  val loadTagHit = loadTagHitOH.reduce(_ || _)
  val loadTagHitWay = OHToUInt(loadTagHitOH)
  val loadHit = loadTagHit
  // * store cache hit or miss
  val storeTag = storeStage(0).addr(XLEN - 1, XLEN - 1 - DCACHE_TAG + 1)
  val storeTagHitOH = tagResp.map(e => e.valid && e.tag === storeTag)
  val storeTagHit = storeTagHitOH.reduce(_ || _)
  val storeTagHitWay = OHToUInt(storeTagHitOH)
  val storeHit = storeTagHit
  // * Amo cache hit or miss
  val amoTag = amoUopReg.addr(XLEN - 1, XLEN - 1 - DCACHE_TAG + 1)
  val amoTagHitOH = tagResp.map(e => e.valid && e.tag === amoTag)
  val amoTagHit = amoTagHitOH.reduce(_ || _)
  val amoTagHitWay = OHToUInt(amoTagHitOH)
  val amoHit = amoTagHit

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
    wmask
  }

  val canServeCacheUop = (!cacheCtrlUopValid || io.OUT_cacheCtrlUop.ready) && 
                         (!cacheUopValidNext || !isMSHRConflict(cacheUopNext)) && 
                          flushState === sFlushIdle

  when(flushState === sFlushActive){
    writeTag := true.B
    io.OUT_tagReq.valid := true.B
    io.OUT_tagReq.bits.addr := Cat(flushIndex, 0.U(log2Up(CACHE_LINE_B).W))
    io.OUT_tagReq.bits.write := true.B
    io.OUT_tagReq.bits.way := flushWay
    io.OUT_tagReq.bits.data.valid := false.B
    io.OUT_tagReq.bits.data.tag := 0.U
  }.elsewhen(loadNeedCacheUop && canServeCacheUop) {
    // * Write tag
    writeTag := true.B
    io.OUT_tagReq.valid := true.B
    io.OUT_tagReq.bits.addr := loadStage(0).addr
    io.OUT_tagReq.bits.write := true.B
    io.OUT_tagReq.bits.way := replaceCounter
    val dtag = Wire(new DTag)
    dtag.valid := true.B
    dtag.tag := loadStage(0).addr(XLEN - 1, XLEN - 1 - DCACHE_TAG + 1)
    io.OUT_tagReq.bits.data := dtag
  }.elsewhen(storeNeedCacheUop && canServeCacheUop) {
    // * Write tag
    writeTag := true.B
    io.OUT_tagReq.valid := true.B
    io.OUT_tagReq.bits.addr := storeStage(0).addr
    io.OUT_tagReq.bits.write := true.B
    io.OUT_tagReq.bits.way := replaceCounter
    val dtag = Wire(new DTag)
    dtag.valid := true.B
    dtag.tag := storeStage(0).addr(XLEN - 1, XLEN - 1 - DCACHE_TAG + 1)
    io.OUT_tagReq.bits.data := dtag
  }.elsewhen(amoNeedCacheUop && canServeCacheUop) {
    // * Write tag
    writeTag := true.B
    io.OUT_tagReq.valid := true.B
    io.OUT_tagReq.bits.addr := amoUopReg.addr
    io.OUT_tagReq.bits.write := true.B
    io.OUT_tagReq.bits.way := replaceCounter
    val dtag = Wire(new DTag)
    dtag.valid := true.B
    dtag.tag := amoUopReg.addr(XLEN - 1, XLEN - 1 - DCACHE_TAG + 1)
    io.OUT_tagReq.bits.data := dtag
  }

  when(loadStageValid(0) || uncachedLSU.io.OUT_loadUop.valid) {
    val bypassData =  Wire(Vec(4, UInt(8.W)))
    val bypassDataMask = io.IN_storeBypassResp.mask | io.IN_storeBufferBypassResp.mask
    for(i <- 0 until 4) {
      bypassData(i) := Mux(
        io.IN_storeBypassResp.mask(i),
        io.IN_storeBypassResp.data((i + 1) * 8 - 1, i * 8),
        io.IN_storeBufferBypassResp.data((i + 1) * 8 - 1, i * 8)
      )
    }
    val loadMask = getWmask(loadStage(0))
    val isInternalMMIO = Addr.isInternalMMIO(loadStage(0).addr)
    val loadAddrAlreadyInFlight = isLoadAddrAlreadyInFlight(loadStage(0).addr)
    val loadCacheHit = ((loadHit || (~bypassDataMask & loadMask) === 0.U) || isInternalMMIO) && !loadAddrAlreadyInFlight
    val wordOffset = loadStage(0).addr(log2Up(CACHE_LINE_B) - 1, 2)
    dontTouch(wordOffset)
    val loadWord = dataResp(loadTagHitWay)(wordOffset).asUInt
    val finalData = {
      val data = Wire(Vec(4, UInt(8.W)))
      for(i <- 0 until 4) {
        data(i) := Mux(
          bypassDataMask(i),
          bypassData(i),
          loadWord((i + 1) * 8 - 1, i * 8)
        )
      }
      data
    }
    val uncacheLoadUop = uncachedLSU.io.OUT_loadUop.bits
    val uncacheLoadData = uncachedLSU.io.OUT_loadData
    // * Uncache Cannot replay, so make sure LoadResultBuffer has space
    val serveUncache = !loadStageValid(0) && loadResultBuffer.io.IN_loadResult.ready
    uncachedLSU.io.OUT_loadUop.ready := serveUncache

    val loadUop = Mux(serveUncache, uncacheLoadUop, loadStage(0))


    // * If there is no space in LoadResultBuffer, must fail

    // * For cached load, miss when [tag miss] || [mshr conflict]
    // * For uncached/internal MMIO load, never miss

    // * One load have two control signals: [miss] and [need cache uop]
    // * negAck: miss && !canServeCacheUop

    val miss = !serveUncache && !isInternalMMIO && !loadCacheHit
    val loadMiss = miss && !loadAddrAlreadyInFlight && loadResultBuffer.io.IN_loadResult.ready    
    // ! ?????? 逻辑一团乱, 跑sum继续debug
    // * NegAck
    loadNegAckValid := false.B
    loadNegAck.dest := loadUop.dest
    loadNegAck.ldqPtr := loadUop.ldqPtr
    // * LoadResult
    loadResultValid := true.B
    loadResult.data := Mux(serveUncache, uncacheLoadData, Mux(isInternalMMIO, io.IN_mmioResp.data, finalData.asUInt))
    loadResult.ready := Mux(serveUncache || isInternalMMIO, true.B, loadCacheHit)
    loadResult.bypassMask := Mux(serveUncache || isInternalMMIO, Fill(XLEN/8, "b1".U), bypassDataMask | ~loadMask)
    loadResult.addr := loadUop.addr
    loadResult.opcode := loadUop.opcode
    loadResult.prd := loadUop.prd
    loadResult.robPtr := loadUop.robPtr
    loadResult.dest := loadUop.dest

    when(!loadResultBuffer.io.IN_loadResult.ready) {
      loadNegAckValid := true.B
    }.elsewhen(miss) {
      when(!loadAddrAlreadyInFlight) {
        loadNeedCacheUop := true.B
        // * Cache Miss Info
        cacheUopValidNext := true.B
        cacheUopNext.index := loadUop.addr(log2Up(CACHE_LINE_B) + log2Up(DCACHE_SETS) - 1, log2Up(CACHE_LINE_B))
        cacheUopNext.rtag := loadUop.addr(XLEN - 1, XLEN - 1 - DCACHE_TAG + 1)
        cacheUopNext.wtag := tagResp(replaceCounter).tag
        cacheUopNext.way := replaceCounter
        cacheUopNext.wmask := 0.U
        cacheUopNext.wdata := 0.U
        cacheUopNext.opcode := Mux(tagResp(replaceCounter).valid, CacheOpcode.REPLACE, CacheOpcode.LOAD)
      }
      
      when(loadNeedCacheUop && !canServeCacheUop) {
        loadNegAckValid := true.B
        loadResultValid := false.B
      }
    }
  }
  when(!loadStageValid(0) && storeStageValid(0)) {
    val memLen = storeStage(0).opcode(2, 1)
    val addrOffset = storeStage(0).addr(log2Up(XLEN/8) - 1, 0)
    val wmask = MuxLookup(memLen, 0.U(4.W))(
      Seq(
        0.U(2.W) -> "b0001".U,
        1.U(2.W) -> "b0011".U,
        2.U(2.W) -> "b1111".U
      )
    ) << addrOffset

    storeAckValid := true.B
    storeAck.index := storeStage(0).stqPtr.index
    storeAck.resp := 1.U
    val storeAddrAlreadyInFlight = isLoadAddrAlreadyInFlight(storeStage(0).addr)
    dontTouch(storeAddrAlreadyInFlight)
    dontTouch(storeHit)
    val isInternalMMIO = Addr.isInternalMMIO(storeStage(0).addr)
    val isUncached = Addr.isUncached(storeStage(0).addr)
    when(isUncached) {
      storeAck.resp := 0.U
    }.elsewhen(isInternalMMIO){
      storeAck.resp := 0.U
    }.elsewhen(storeHit && !storeAddrAlreadyInFlight) {
      val offset = storeStage(0).addr(log2Up(CACHE_LINE_B) - 1, 2)
      // * Cache Hit - Write data
      io.OUT_dataReq.valid := true.B
      io.OUT_dataReq.bits.addr := storeStage(0).addr
      io.OUT_dataReq.bits.write := true.B
      io.OUT_dataReq.bits.way := storeTagHitWay
      io.OUT_dataReq.bits.wmask := wmask << (offset * 4.U)
      io.OUT_dataReq.bits.data := storeStage(0).wdata << (storeStage(0).addr(log2Up(CACHE_LINE_B) - 1, 0) << 3)
      storeWriteData := true.B
      when (io.OUT_dataReq.ready) {     
        storeAck.resp := 0.U 
      }
    }.elsewhen(!storeAddrAlreadyInFlight) {
      storeNeedCacheUop := true.B
      // * Cache Controller
      cacheUopValidNext := true.B  
      cacheUopNext.index := storeStage(0).addr(log2Up(CACHE_LINE_B) + log2Up(DCACHE_SETS) - 1, log2Up(CACHE_LINE_B))
      cacheUopNext.rtag := storeStage(0).addr(XLEN - 1, XLEN - 1 - DCACHE_TAG + 1)
      cacheUopNext.way := replaceCounter
      cacheUopNext.wtag := tagResp(replaceCounter).tag
      cacheUopNext.wdata := 0.U
      cacheUopNext.wmask := 0.U
      cacheUopNext.offset := storeStage(0).addr(log2Up(CACHE_LINE_B) - 1, 0)
      cacheUopNext.opcode := Mux(tagResp(replaceCounter).valid, CacheOpcode.REPLACE, CacheOpcode.LOAD)
      
      // when (canServeCacheUop) {
      //   storeAck.resp := 0.U
      // }
    }
  }

  val amoIsLr = amoUopReg.opcode === AMOOp.LR_W
  val amoIsSc = amoUopReg.opcode === AMOOp.SC_W  
  val amoLoadData = Reg(UInt(XLEN.W))
  val amoStoreData = Reg(UInt(XLEN.W))
  val amoHitWayReg = Reg(UInt(log2Up(DCACHE_WAYS).W))
  val amoSuccess = WireInit(false.B)
  val scFail = RegInit(false.B)

  val amoWriteback = Wire(new WritebackUop)
  val amoCanWriteback = WireInit(false.B)

  when(amoIsSc) {
    amoWriteback.data := scFail
  }.otherwise {
    amoWriteback.data := amoLoadData    
  }
  amoWriteback.prd := amoUopReg.prd
  amoWriteback.robPtr := amoUopReg.robPtr
  amoWriteback.flag := 0.U
  amoWriteback.target := 0.U
  amoWriteback.dest := amoUopReg.dest

  amoALU.io.IN_opcode := amoUopReg.opcode
  amoALU.io.IN_src1 := amoLoadData
  amoALU.io.IN_src2 := amoUopReg.wdata
  when(amoState === sAmoALU) {
    amoStoreData := amoALU.io.OUT_res
  }

  val inScFail =  !reservationValid || io.IN_amoUop.bits.addr =/= reservation

  switch(amoState) {
    is(sAmoIdle) {
      when(amoUop) {        
        when(inAmoIsSc && inScFail) {
          scFail := true.B
          amoState := sAmoWriteback
        }.otherwise {
          scFail := false.B
          amoState := sAmoLoad
        }
      }
    }
    is(sAmoLoad) {
      val amoAddrAlreadyInFlight = isLoadAddrAlreadyInFlight(amoUopReg.addr)
      // * Tag / Data is available
      when(!amoHit || amoAddrAlreadyInFlight) {
        amoAckValid := true.B
        amoAck.resp := 1.U      
      }

      when(!amoHit && !amoAddrAlreadyInFlight) {
        amoNeedCacheUop := true.B
        // * Cache Controller           
        cacheUopValidNext := true.B
        cacheUopNext.index := amoUopReg.addr(log2Up(CACHE_LINE_B) + log2Up(DCACHE_SETS) - 1, log2Up(CACHE_LINE_B))
        cacheUopNext.rtag := amoUopReg.addr(XLEN - 1, XLEN - 1 - DCACHE_TAG + 1)
        cacheUopNext.wtag := tagResp(replaceCounter).tag
        cacheUopNext.way := replaceCounter
        cacheUopNext.wmask := 0.U
        cacheUopNext.wdata := 0.U
        cacheUopNext.opcode := Mux(tagResp(replaceCounter).valid, CacheOpcode.REPLACE, CacheOpcode.LOAD)
        cacheUopNext.offset := amoUopReg.addr(log2Up(CACHE_LINE_B) - 1, 0)
      }

      amoHitWayReg := amoTagHitWay
      amoLoadData := dataResp(amoTagHitWay)(amoUopReg.addr(log2Up(CACHE_LINE_B) - 1, 2))
      amoState := Mux(amoHit && !amoAddrAlreadyInFlight, Mux(amoIsSc, sAmoStore, Mux(amoIsLr, sAmoWriteback, sAmoALU)), sAmoIdle)
    }
    is(sAmoALU) {
      amoState := sAmoStore
    }
    is(sAmoStore) {
      val offset = amoUopReg.addr(log2Up(CACHE_LINE_B) - 1, 2)
      io.OUT_dataReq.valid := true.B
      io.OUT_dataReq.bits.addr := amoUopReg.addr
      io.OUT_dataReq.bits.write := true.B
      io.OUT_dataReq.bits.way := amoHitWayReg
      io.OUT_dataReq.bits.wmask := "b1111".U << (offset * 4.U)
      io.OUT_dataReq.bits.data := Mux(amoIsSc, amoUopReg.wdata, amoStoreData) << (offset * 32.U)

      amoSuccess := io.OUT_dataReq.ready
      
      amoAckValid := true.B
      amoAck.resp := Mux(amoSuccess, 0.U, 1.U)

      amoState := Mux(amoSuccess, sAmoWriteback, sAmoIdle)
    }
    is(sAmoWriteback) {
      when(amoCanWriteback) {
        when(amoIsLr) {
          reservation := amoUopReg.addr
          reservationValid := true.B
        }.elsewhen(amoIsSc) {
          reservationValid := false.B
        }
        amoState := sAmoIdle
      }
    }
  }

  when(canServeCacheUop) {
    cacheCtrlUopValid := cacheUopValidNext
    cacheCtrlUop := cacheUopNext
  }

  io.OUT_loadNegAck.valid := loadNegAckValid
  io.OUT_loadNegAck.bits := loadNegAck

  io.OUT_storeAck.valid := storeAckValid
  io.OUT_storeAck.bits := storeAck

  io.OUT_amoAck.valid := amoAckValid
  io.OUT_amoAck.bits := amoAck
  
  // * Output 
  when(loadResultBuffer.io.OUT_writebackUop.valid) {
    io.OUT_writebackUop := loadResultBuffer.io.OUT_writebackUop
  }.otherwise {
    amoCanWriteback := true.B
    io.OUT_writebackUop.valid := amoState === sAmoWriteback
    io.OUT_writebackUop.bits := amoWriteback
  }
}

class LoadResult extends CoreBundle{
  val data = UInt(XLEN.W)
  val ready = Bool()
  // * already bypassed mask
  val bypassMask = UInt(4.W)
  // * addr
  val addr = UInt(XLEN.W)
  val opcode = UInt(OpcodeWidth.W)
  val prd = UInt(PREG_IDX_W)
  val robPtr = RingBufferPtr(ROB_SIZE)
  val dest = UInt(1.W)
}

class LoadResultBufferIO extends CoreBundle {
  val IN_loadResult = Flipped(Decoupled(new LoadResult))
  val IN_memLoadFoward = Flipped(Valid(new MemLoadFoward))
  val OUT_writebackUop = Valid(new WritebackUop)

  val IN_flush = Flipped(Bool())
}

class LoadResultBuffer(N: Int = 8) extends CoreModule with HasLSUOps {
  val io = IO(new LoadResultBufferIO)
  
  // Load result entries
  val valid = RegInit(VecInit(Seq.fill(N)(false.B)))
  val entries = Reg(Vec(N, new LoadResult))
  
  // Find an empty slot for new load
  val emptySlots = valid.map(!_) 
  val hasEmptySlot = emptySlots.reduce(_ || _)
  val allocPtr = PriorityEncoder(emptySlots)
  
  io.IN_loadResult.ready := hasEmptySlot

  val inLoadResult = io.IN_loadResult.bits
  // * Write back now, without writing to result queue
  val inLoadResultWriteback = io.IN_loadResult.fire && inLoadResult.ready

  // * Accept new load if there's space and the loadResult is not ready
  when(io.IN_loadResult.fire && !inLoadResult.ready && (!io.IN_flush || io.IN_loadResult.bits.dest === Dest.PTW)) {
    valid(allocPtr) := true.B
    entries(allocPtr) := io.IN_loadResult.bits
  }
  
  // Forward load data to all entries
  for (i <- 0 until N) {
    when(valid(i) && io.IN_memLoadFoward.valid &&
          io.IN_memLoadFoward.bits.addr(XLEN - 1, log2Up(AXI_DATA_WIDTH / 8)) === entries(i).addr(XLEN - 1, log2Up(AXI_DATA_WIDTH / 8))) {
      val data = Wire(Vec(4, UInt(8.W)))
      data := entries(i).data.asTypeOf(data)
      val offset = Cat(entries(i).addr(log2Up(AXI_DATA_WIDTH / 8) - 1, 2), 0.U(2.W))
      val bytes = Wire(Vec(AXI_DATA_WIDTH / 8, UInt(8.W)))
      bytes := io.IN_memLoadFoward.bits.data.asTypeOf(bytes)
      for (j <- 0 until 4) {
        when(!entries(i).bypassMask(j)) {
          data(j) := bytes(offset + j.U)
        }
      }
      entries(i).data := data.asUInt
      entries(i).ready := true.B
    }
  }

  // Find ready entries to writeback
  val readyEntries = valid.zip(entries).map { case (v, e) => v && e.ready }
  val hasReady = readyEntries.reduce(_ || _)
  val readyIndex = PriorityEncoder(readyEntries)
  
  val wbUopValid = RegInit(false.B)
  val wbUop = Reg(new WritebackUop)

  // Generate writeback when entry is ready
  io.OUT_writebackUop.valid := hasReady

  def loadResultToWriteback(loadResult: LoadResult) = {
    val wbUopNext = Wire(new WritebackUop)
    val addrOffset = loadResult.addr(1, 0)
    val rawData = loadResult.data >> (addrOffset << 3)
    val loadU = loadResult.opcode(0)
    val memLen = loadResult.opcode(2,1)
    val shiftedData = MuxCase(rawData, Seq(
      (memLen === BYTE) -> Cat(Fill(24, ~loadU & rawData(7)), rawData(7,0)),
      (memLen === HALF) -> Cat(Fill(16, ~loadU & rawData(15)), rawData(15,0))
    ))

    wbUopNext.prd := loadResult.prd
    wbUopNext.data := shiftedData
    wbUopNext.robPtr := loadResult.robPtr
    wbUopNext.dest := loadResult.dest
    wbUopNext.flag := 0.U
    wbUopNext.target := 0.U
    wbUopNext
  }

  // * New load result with ready data writes back first
  val wbLoadResult = Mux(inLoadResultWriteback, inLoadResult, entries(readyIndex))

  wbUopValid := inLoadResultWriteback || hasReady
  wbUop := loadResultToWriteback(wbLoadResult)

  io.OUT_writebackUop.valid := wbUopValid
  io.OUT_writebackUop.bits := wbUop

  when(inLoadResultWriteback) {

  }.elsewhen(hasReady) {
    // Clear entry after writeback
    valid(readyIndex) := false.B
  }

  when(io.IN_flush) {
    when(wbLoadResult.dest === Dest.ROB) {
      wbUopValid := false.B
    }
    
    for(i <- 0 until N) {
      when(valid(i) && entries(i).dest === Dest.ROB) {
        valid(i) := false.B
      }
    }
  }
}

class UncachedLSUIO extends CoreBundle {
  val IN_loadUop = Flipped(Decoupled(new AGUUop))
  val IN_storeUop = Flipped(Decoupled(new AGUUop))
  val IN_memLoadFoward = Flipped(Valid(new MemLoadFoward))
  val IN_uncacheStoreResp = Flipped(Bool())
  val OUT_cacheCtrlUop = Decoupled(new CacheCtrlUop)

  val OUT_loadUop = Decoupled(new AGUUop)
  val OUT_loadData = UInt(XLEN.W)

  // * [robTailPtr change] -> [flush]
  // *           |----------> [loadUop]
  // * do not flush Store
  val IN_flush = Flipped(Bool())
}

class UncachedLSU extends CoreModule {
  val io = IO(new UncachedLSUIO)

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
    wmask
  }

  val sIdle :: sLoadReq :: sStoreReq :: sWaitLoadResp :: sWaitStoreResp :: sLoadFin :: Nil = Enum(6)
  val state = RegInit(sIdle)
  val cacheCtrlUop = Reg(new CacheCtrlUop)
  cacheCtrlUop := 0.U.asTypeOf(new CacheCtrlUop)
  cacheCtrlUop.cacheId := CacheId.DCACHE

  val loadUop = Reg(new AGUUop)
  val loadData = Reg(UInt(XLEN.W))

  io.IN_loadUop.ready := state === sIdle
  io.IN_storeUop.ready := (state === sIdle && !io.IN_loadUop.valid)

  switch (state) {
    is (sIdle) {
      when (io.IN_loadUop.valid && !io.IN_flush) {
        state := sLoadReq
        loadUop := io.IN_loadUop.bits

        cacheCtrlUop.index := io.IN_loadUop.bits.addr(log2Up(CACHE_LINE_B) + log2Up(DCACHE_SETS) - 1, log2Up(CACHE_LINE_B))
        cacheCtrlUop.rtag := io.IN_loadUop.bits.addr(XLEN - 1, XLEN - 1 - DCACHE_TAG + 1)
        cacheCtrlUop.offset := io.IN_loadUop.bits.addr(log2Up(CACHE_LINE_B) - 1, 0)
        cacheCtrlUop.opcode := MuxLookup(io.IN_loadUop.bits.opcode, CacheOpcode.UNCACHED_LB)(Seq(
          LSUOp.LB -> CacheOpcode.UNCACHED_LB,
          LSUOp.LBU -> CacheOpcode.UNCACHED_LB,
          LSUOp.LH -> CacheOpcode.UNCACHED_LH,
          LSUOp.LHU -> CacheOpcode.UNCACHED_LH,
          LSUOp.LW -> CacheOpcode.UNCACHED_LW
        ))


      }.elsewhen(io.IN_storeUop.valid) {
        state := sStoreReq

        cacheCtrlUop.index := io.IN_storeUop.bits.addr(log2Up(CACHE_LINE_B) + log2Up(DCACHE_SETS) - 1, log2Up(CACHE_LINE_B))
        cacheCtrlUop.wtag := io.IN_storeUop.bits.addr(XLEN - 1, XLEN - 1 - DCACHE_TAG + 1)
        cacheCtrlUop.offset := io.IN_storeUop.bits.addr(log2Up(CACHE_LINE_B) - 1, 0)
        cacheCtrlUop.opcode := MuxLookup(io.IN_storeUop.bits.opcode, CacheOpcode.UNCACHED_SB)(Seq(
          LSUOp.SB -> CacheOpcode.UNCACHED_SB,
          LSUOp.SH -> CacheOpcode.UNCACHED_SH,
          LSUOp.SW -> CacheOpcode.UNCACHED_SW
        ))
        cacheCtrlUop.wdata := io.IN_storeUop.bits.wdata
        cacheCtrlUop.wmask := getWmask(io.IN_storeUop.bits)
      }
    }
    is (sLoadReq) {
      when(io.OUT_cacheCtrlUop.fire) {
        state := sWaitLoadResp
      }      
    }
    is (sStoreReq) {
      when(io.OUT_cacheCtrlUop.fire) {
        state := sWaitStoreResp
      }
    }
    is (sWaitLoadResp) {
      when(io.IN_memLoadFoward.valid && io.IN_memLoadFoward.bits.uncached) {        
        state := sLoadFin
        val offset = loadUop.addr(log2Up(AXI_DATA_WIDTH / 8) - 1, 2)
        val dataVec = Wire(Vec(AXI_DATA_WIDTH / 32, UInt(32.W)))
        dataVec := io.IN_memLoadFoward.bits.data.asTypeOf(dataVec)
        loadData := dataVec(offset)
      }
    }
    is (sWaitStoreResp) {
      when(io.IN_uncacheStoreResp) {
        state := sIdle
      }
    }
    is(sLoadFin) {
      when(io.OUT_loadUop.fire) {
        state := sIdle
      }
    }
  }

  io.OUT_cacheCtrlUop.valid := state === sLoadReq || state === sStoreReq
  io.OUT_cacheCtrlUop.bits := cacheCtrlUop

  io.OUT_loadUop.valid := state === sLoadFin
  io.OUT_loadUop.bits := loadUop
  io.OUT_loadData := loadData
}