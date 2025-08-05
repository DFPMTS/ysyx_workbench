import chisel3._
import chisel3.util._
import utils._
import os.stat
import dataclass.data
import os.write
import MSHRChecker.isInFlightAddrDataAvailable

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
  val notReady = UInt(4.W)
}

class VirtualIndex extends CoreBundle {
  val index = UInt(log2Up(DCACHE_SETS).W)
  val opcode = UInt(OpcodeWidth.W)
  val mask = UInt(4.W)
}

class NewLSUIO extends CoreBundle {
  // * VIPT Load Interface
  val IN_aguVirtualIndex = Flipped(Decoupled(new VirtualIndex))
  val IN_aguLoadUop = Flipped(Valid(new AGUUop))
  // * Regular Load Store Interface
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
  val OUT_tagRead = Decoupled(new DTagReq)
  val IN_tagResp = Flipped(new DTagResp)
  val OUT_tagWrite = Decoupled(new DTagReq)

  val OUT_dataRead = Decoupled(new DDataReq)
  val IN_dataResp = Flipped(new DDataResp)
  val OUT_dataWrite = Decoupled(new DDataReq)
  // * Internal MMIO Interface (CLINT)
  val OUT_mmioReq = new MMIOReq
  val IN_mmioResp = Flipped(new MMIOResp)
  // * Cache Controller Interface
  val OUT_cacheCtrlUop = Decoupled(new CacheCtrlUop)
  val OUT_uncacheUop = Decoupled(new CacheCtrlUop)
  val IN_mshrs = Flipped(Vec(NUM_MSHR, new MSHR))
  val IN_memLoadFoward = Flipped(Valid(new MemLoadFoward))
  val IN_uncacheStoreResp = Flipped(Bool())
  val OUT_dirty = Vec(DCACHE_WAYS, Vec(DCACHE_SETS, Bool()))

  val OUT_writebackUop = Valid(new WritebackUop)

  val IN_storeQueueEmpty = Flipped(Bool())
  val IN_storeBufferEmpty = Flipped(Bool())

  val IN_flushDCache = Flipped(Bool())
  val OUT_flushBusy = Bool()

  // * One cycle before the writeback
  val OUT_wakeUp = Valid(new WritebackUop)
  
  // * Is there LSUOp in the pipeline?
  val OUT_busy = Bool()
  // * LLB Control
  val IN_clearLLB = Flipped(Bool())
  val OUT_LLB = Bool()

  val IN_flush = Flipped(Bool())
}

class NewLSU extends CoreModule with HasLSUOps {
  val io = IO(new NewLSUIO)

  // * dummy counter for replacement
  val replaceCounter = RegInit(0.U(log2Up(DCACHE_WAYS).W))
  replaceCounter := Mux(replaceCounter === DCACHE_WAYS.U - 1.U, 0.U, replaceCounter + 1.U)

  // * Util to get the DCache index
  def getDCacheIndex(addr: UInt): UInt = {
    addr(log2Up(CACHE_LINE_B) + log2Up(DCACHE_SETS) - 1, log2Up(CACHE_LINE_B))
  }

  // * Dirty Table
  val dirty = RegInit(0.U.asTypeOf(Vec(DCACHE_WAYS, Vec(DCACHE_SETS, Bool()))))
  io.OUT_dirty := dirty
  val setDirtyValid = WireInit(false.B)
  val setDirtyWay = Wire(UInt(log2Up(DCACHE_WAYS).W))
  val setDirtyIndex = Wire(UInt(log2Up(DCACHE_SETS).W))

  val clearDirtyValid = WireInit(false.B)
  val clearDirtyWay = Wire(UInt(log2Up(DCACHE_WAYS).W))
  val clearDirtyIndex = Wire(UInt(log2Up(DCACHE_SETS).W))

  when(setDirtyValid) {
    dirty(setDirtyWay)(setDirtyIndex) := true.B
  }
  when(clearDirtyValid) {
    dirty(clearDirtyWay)(clearDirtyIndex) := false.B
  }

  // * Submodules
  val loadResultBuffer = Module(new LoadResultBuffer)
  val uncachedLSU = Module(new UncachedLSU)
  val amoALU = Module(new AMOALU)

  io.OUT_cacheCtrlUop.valid := false.B
  io.OUT_cacheCtrlUop.bits := DontCare
  io.OUT_dataRead.valid := true.B
  io.OUT_dataRead.bits := DontCare
  io.OUT_tagRead.valid := true.B
  io.OUT_tagRead.bits := DontCare

  io.OUT_tagWrite.valid := false.B
  io.OUT_tagWrite.bits := DontCare
  io.OUT_dataWrite.valid := false.B
  io.OUT_dataWrite.bits := DontCare

  // * DCache Flush State Machine
  val flushNeedWriteback = RegInit(false.B)
  val sFlushIdle :: sFlushActive :: sFlushWaitStart :: sFlushWriteTag :: sFlushRead :: sFlushWaitEnd :: Nil = Enum(6)
  val flushState = RegInit(sFlushActive)
  val flushIndex = RegInit(0.U(log2Up(DCACHE_SETS).W))
  val flushWay = RegInit(0.U(log2Up(DCACHE_WAYS).W))

  io.OUT_flushBusy := flushState =/= sFlushIdle

  // * write data
  val writeTag = RegInit(false.B)
  val storeWriteData = WireInit(false.B)
  val amoWriteData = WireInit(false.B)

  // * Load/Store Pipeline
  val stage = Reg(Vec(2, new AGUUop))
  val stageValid = RegInit(VecInit(Seq.fill(2)(false.B)))
  val needAGULoadUop = RegInit(false.B)
  val cacheMiss = RegInit(false.B)
  cacheMiss := false.B
  val cacheHit = RegInit(false.B)
  cacheHit := false.B
  val cacheLoadData = Reg(UInt(XLEN.W))
  val needCacheUop = RegInit(false.B)
  needCacheUop := false.B
  val loadDiscard = RegInit(false.B)
  loadDiscard := false.B
  val replaceTag = Reg(UInt(DCACHE_TAG.W))
  val replaceWay = Reg(UInt(log2Up(DCACHE_WAYS).W))
  val replaceOpcode = Reg(UInt(OpcodeWidth.W))

  // * Amo State Machine
  val sAmoIdle :: sAmoLoad :: sAmoALU :: sAmoStore :: sAmoWriteback :: Nil = Enum(5)
  val amoState = RegInit(sAmoIdle)
  val amoUopReg = Reg(new AGUUop)

  // * Reservation 
  val reservation = Reg(UInt(XLEN.W))
  val reservationValid = RegInit(false.B)
  io.OUT_LLB := reservationValid
  when(io.IN_clearLLB) {
    reservationValid := false.B
  }

  // * Cache Control Request 
  val cacheCtrlUop = Reg(new CacheCtrlUop)
  val cacheCtrlUopValid = RegInit(false.B)  

  val cacheUopReq = Wire(new CacheCtrlUop)
  cacheUopReq := DontCare
  cacheUopReq.cacheId := CacheId.DCACHE
  val cacheUopReqValid = WireInit(false.B)
  
  io.OUT_cacheCtrlUop.valid := cacheCtrlUopValid
  io.OUT_cacheCtrlUop.bits := cacheCtrlUop
  when(io.OUT_cacheCtrlUop.fire) {  
    cacheCtrlUopValid := false.B
  }

  def isLoadAddrAlreadyInFlight(addr: UInt) = {
    MSHRChecker.isLoadAddrAlreadyInFlight(io.IN_mshrs, io.OUT_cacheCtrlUop, CacheId.DCACHE, addr)
  }

  def isInFlightAddrDataAvailable(addr: UInt) = {
    MSHRChecker.isInFlightAddrDataAvailable(io.IN_mshrs, io.OUT_cacheCtrlUop, CacheId.DCACHE, addr)
  }

  def isMSHRConflict(uop: CacheCtrlUop) = {
    MSHRChecker.conflict(io.IN_mshrs, io.OUT_cacheCtrlUop, uop)
  }

  val idle = !writeTag && io.OUT_dataRead.ready && io.OUT_tagRead.ready && amoState === sAmoIdle && flushState === sFlushIdle
  // ** For now, we only support one load/store at a time
  val serveAmo = idle && io.IN_amoUop.valid
  val serveVirtualIndex = idle && !io.IN_amoUop.valid
  val serveLoad = idle && (!io.IN_loadUop.bits.isUncached || uncachedLSU.io.IN_loadUop.ready) && !io.IN_amoUop.valid && !io.IN_aguVirtualIndex.valid
  val serveStore = idle && (!io.IN_storeUop.bits.isUncached || uncachedLSU.io.IN_storeUop.ready) && !io.IN_amoUop.valid && !io.IN_loadUop.valid && !io.IN_aguVirtualIndex.valid

  io.IN_amoUop.ready := serveAmo
  io.IN_aguVirtualIndex.ready := serveVirtualIndex
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
  val loadIsUncached = inLoadUop.isUncached
  val loadUop = io.IN_loadUop.valid && serveLoad && !loadIsUncached

  val inStoreUop = io.IN_storeUop.bits
  val storeIsUncached = inStoreUop.isUncached
  val storeUop = io.IN_storeUop.valid && serveStore

  val inAGUVirtualIndex = io.IN_aguVirtualIndex.bits
  val aguVirtualIndex = io.IN_aguVirtualIndex.valid && serveVirtualIndex

  stageValid(0) := (loadUop && (!io.IN_flush || inLoadUop.dest === Dest.PTW)) || storeUop || aguVirtualIndex
  stage(0) := Mux(storeUop, inStoreUop, inLoadUop)
  when(aguVirtualIndex) {
    stage(0).addr := Cat(inAGUVirtualIndex.index, 0.U(log2Up(CACHE_LINE_B).W))
    stage(0).opcode := inAGUVirtualIndex.opcode
    stage(0).dest := Dest.ROB
    stage(0).mask := inAGUVirtualIndex.mask
  }
  needAGULoadUop := aguVirtualIndex

  // * Uncache Load/Store
  uncachedLSU.io.IN_loadUop.valid := io.IN_loadUop.valid && loadIsUncached && serveLoad
  uncachedLSU.io.IN_loadUop.bits := io.IN_loadUop.bits
  uncachedLSU.io.IN_storeUop.valid := io.IN_storeUop.valid && storeIsUncached && serveStore
  uncachedLSU.io.IN_storeUop.bits := io.IN_storeUop.bits
  uncachedLSU.io.IN_memLoadFoward := io.IN_memLoadFoward
  uncachedLSU.io.IN_uncacheStoreResp := io.IN_uncacheStoreResp

  io.OUT_mmioReq := uncachedLSU.io.OUT_mmioReq
  uncachedLSU.io.IN_mmioResp := io.IN_mmioResp

  uncachedLSU.io.IN_flush := io.IN_flush

  uncachedLSU.io.OUT_cacheCtrlUop.ready := false.B
  uncachedLSU.io.OUT_loadResult.ready := false.B

  io.OUT_uncacheUop <> uncachedLSU.io.OUT_cacheCtrlUop

  val loadStage0 = Mux(needAGULoadUop, io.IN_aguLoadUop.bits, stage(0))
  // * Query Store Queue Bypass, resp available in next cycle (loadStage(0))
  io.OUT_storeBypassReq.addr := loadStage0.addr
  io.OUT_storeBypassReq.stqPtr := loadStage0.stqPtr

  // * Amo 
  val inAmoUop = io.IN_amoUop.bits
  val inAmoIsLr = inAmoUop.opcode === AMOOp.LR_W
  val inAmoIsSc = inAmoUop.opcode === AMOOp.SC_W
  val amoUop = io.IN_amoUop.valid && serveAmo
  amoUopReg := inAmoUop

  when(flushState === sFlushRead) {
    io.OUT_tagRead.bits.addr := Cat(flushIndex, 0.U(log2Up(CACHE_LINE_B).W))
  }.elsewhen(aguVirtualIndex) {
    // * Tag Request
    io.OUT_tagRead.bits.addr := Cat(inAGUVirtualIndex.index, 0.U(log2Up(CACHE_LINE_B).W))
    // * Data Request
    io.OUT_dataRead.bits.addr := Cat(inAGUVirtualIndex.index, 0.U(log2Up(CACHE_LINE_B).W))
  }.elsewhen(loadUop) {
    // * Tag Request
    io.OUT_tagRead.bits.addr := inLoadUop.addr
    // * Data Request
    io.OUT_dataRead.bits.addr := inLoadUop.addr
  }.elsewhen(storeUop) {
    // * Tag Request
    io.OUT_tagRead.bits.addr := inStoreUop.addr
  }.elsewhen(amoUop) { // amoState === sAmoIdle && amoUop
    // * Tag
    io.OUT_tagRead.bits.addr := inAmoUop.addr
    // * Data
    io.OUT_dataRead.bits.addr := inAmoUop.addr
  }

  // val loadNeedCacheUop = WireInit(false.B)
  // val storeNeedCacheUop = WireInit(false.B)
  val amoNeedCacheUop = WireInit(false.B)
  amoNeedCacheUop := false.B
  // dontTouch(loadNeedCacheUop)
  // dontTouch(storeNeedCacheUop)
  // dontTouch(amoNeedCacheUop)

  // ** Load/Store Stage 1
  
  // * Hit Load Result (Stage 1)
  val hitLoadResult = WireInit(0.U.asTypeOf(new LoadResult))
  val hitLoadResultValid = WireInit(false.B)
  hitLoadResultValid := false.B
  // * Miss Load Result (Stage 2)
  val loadResult = Reg(new LoadResult)
  val loadResultValid = RegInit(false.B)
  loadResultValid := false.B
  
  loadResultBuffer.io.IN_hitLoadResult.valid := hitLoadResultValid
  loadResultBuffer.io.IN_hitLoadResult.bits := hitLoadResult
  loadResultBuffer.io.IN_memLoadFoward := io.IN_memLoadFoward
  loadResultBuffer.io.IN_flush := io.IN_flush
  
  // * Load cache hit or miss 
  val tag = Mux(needAGULoadUop, io.IN_aguLoadUop.bits.addr, stage(0).addr)(XLEN - 1, XLEN - 1 - DCACHE_TAG + 1)
  val tagHitOH = tagResp.map(e => e.valid && e.tag === tag)
  val tagHit = tagHitOH.reduce(_ || _)
  val tagHitWay = OHToUInt(tagHitOH)

  // * Amo cache hit or miss
  val amoTag = amoUopReg.addr(XLEN - 1, XLEN - 1 - DCACHE_TAG + 1)
  val amoTagHitOH = tagResp.map(e => e.valid && e.tag === amoTag)
  val amoTagHit = amoTagHitOH.reduce(_ || _)
  val amoTagHitWay = OHToUInt(amoTagHitOH)

  val canServeCacheUop = (!cacheCtrlUopValid || io.OUT_cacheCtrlUop.ready) && 
                         (!cacheUopReqValid || (!isMSHRConflict(cacheUopReq) && !isLoadAddrAlreadyInFlight(Cat(cacheUopReq.rtag, cacheUopReq.index, 0.U(log2Up(CACHE_LINE_B).W)))))
  // val cacheUopTagReq = WireInit(0.U.asTypeOf(new DTagReq))
  val cacheUopTagReq = Reg(new DTagReq)
  writeTag := false.B
  when(writeTag) {
    io.OUT_tagWrite.valid := true.B
    io.OUT_tagWrite.bits := cacheUopTagReq
  }
  when(flushState === sFlushActive){
    writeTag := true.B
    cacheUopTagReq.addr := Cat(flushIndex, 0.U(log2Up(CACHE_LINE_B).W))
    cacheUopTagReq.write := true.B
    cacheUopTagReq.way := flushWay
    cacheUopTagReq.data.valid := false.B
    cacheUopTagReq.data.tag := 0.U
  }.elsewhen(cacheUopReqValid && canServeCacheUop) {
    // * Write tag
    writeTag := true.B
    val addr = Mux(amoNeedCacheUop, amoUopReg.addr, stage(1).addr)
    cacheUopTagReq.addr := addr
    cacheUopTagReq.write := true.B
    cacheUopTagReq.way := Mux(amoNeedCacheUop, replaceCounter, replaceWay)
    val dtag = Wire(new DTag)
    dtag.valid := true.B
    dtag.tag := addr(XLEN - 1, XLEN - 1 - DCACHE_TAG + 1)
    cacheUopTagReq.data := dtag
  }

  when(flushState === sFlushActive) {
    clearDirtyValid := true.B
    clearDirtyWay := flushWay
    clearDirtyIndex := flushIndex
  }.otherwise {
    clearDirtyValid := io.OUT_cacheCtrlUop.fire
    clearDirtyWay := io.OUT_cacheCtrlUop.bits.way
    clearDirtyIndex := io.OUT_cacheCtrlUop.bits.index
  }


  stageValid(1) := false.B
  stage(1) := Mux(needAGULoadUop, io.IN_aguLoadUop.bits, stage(0))

  val stage0Index = getDCacheIndex(stage(0).addr)

  replaceWay := replaceCounter
  replaceTag := tagResp(replaceCounter).tag
  replaceOpcode := Mux(tagResp(replaceCounter).valid, CacheOpcode.REPLACE, CacheOpcode.LOAD)

  val addrInFlight = isLoadAddrAlreadyInFlight(loadStage0.addr)
  val inFlightAddrDataAvailable = isInFlightAddrDataAvailable(loadStage0.addr)

  val wordOffset = loadStage0.addr(log2Up(CACHE_LINE_B) - 1, 2)
  val loadHitCacheline = Mux1H(tagHitOH, dataResp)
  val loadWord = loadHitCacheline(wordOffset)
  cacheLoadData := loadWord

  when(stageValid(0)) {
    val mask = stage(0).mask
    when(LSUOp.isLoad(stage(0).opcode)) {
      val virtualIndexMatch = stage(0).addr(log2Up(CACHE_LINE_B) + log2Up(DCACHE_SETS) - 1, log2Up(CACHE_LINE_B)) === io.IN_aguLoadUop.bits.addr(log2Up(CACHE_LINE_B) + log2Up(DCACHE_SETS) - 1, log2Up(CACHE_LINE_B)) && io.IN_aguLoadUop.valid
      stageValid(1) := (!io.IN_flush || stage(0).dest === Dest.PTW) && (!needAGULoadUop || io.IN_aguLoadUop.valid)
      loadDiscard := needAGULoadUop && !virtualIndexMatch
      cacheHit := tagHit && (!addrInFlight || inFlightAddrDataAvailable) && !writeTag
      cacheMiss := !(tagHit && !addrInFlight && !writeTag)
      needCacheUop := !addrInFlight && !writeTag
    }.otherwise {
      // * Store
      storeAckValid := true.B
      storeAck.index := stage(0).stqPtr.index
      storeAck.resp := 1.U
      dontTouch(tagHit)
      val isUncached = stage(0).isUncached
      val isCached = !isUncached
      val storeMiss = !isUncached && !tagHit && !addrInFlight

      val dirtyConflict = clearDirtyValid && getDCacheIndex(stage(0).addr) === clearDirtyIndex && clearDirtyWay === tagHitWay
      
      cacheHit := isUncached || (tagHit && !addrInFlight && !writeTag)
      cacheMiss := !isUncached && !(tagHit && !addrInFlight && !writeTag)
      needCacheUop := !addrInFlight && !writeTag

      when(!isUncached && !(tagHit && !addrInFlight && !dirtyConflict)) {
        stageValid(1) := true.B
      }

      when(isUncached) {
        storeAck.resp := 0.U
      }.elsewhen(tagHit && !addrInFlight && !dirtyConflict) {
        val offset = stage(0).addr(log2Up(CACHE_LINE_B) - 1, 2)
        // * Cache Hit - Write data
        io.OUT_dataWrite.valid := true.B
        io.OUT_dataWrite.bits.addr := stage(0).addr
        io.OUT_dataWrite.bits.write := true.B
        io.OUT_dataWrite.bits.way := tagHitWay
        io.OUT_dataWrite.bits.wmask := mask << (offset * 4.U)
        io.OUT_dataWrite.bits.data := stage(0).wdata << (stage(0).addr(log2Up(CACHE_LINE_B) - 1, 2) * 32.U)
        storeWriteData := true.B
        when (io.OUT_dataWrite.ready) {     
          storeAck.resp := 0.U 
        }
      }
    }
  }

  setDirtyValid := storeWriteData || amoWriteData
  setDirtyWay := Mux(amoState === sAmoStore, amoTagHitWay, tagHitWay)
  setDirtyIndex := Mux(amoState === sAmoStore, getDCacheIndex(amoUopReg.addr), getDCacheIndex(stage(0).addr))

  // * Stage 1 Signal: CacheMiss CacheLoadData

  val bypassData = Reg(Vec(4, UInt(8.W)))
  val bypassDataMaskNext = io.IN_storeBypassResp.mask | io.IN_storeBufferBypassResp.mask
  val bypassDataMask = RegNext(bypassDataMaskNext)
  val bypassDataHit = RegNext((~bypassDataMaskNext & stage(0).mask) === 0.U)
  val bypassDataNotReady = RegNext((io.IN_storeBypassResp.notReady & stage(0).mask) =/= 0.U)
  for(i <- 0 until 4) {
    bypassData(i) := Mux(
      io.IN_storeBypassResp.mask(i),
      io.IN_storeBypassResp.data((i + 1) * 8 - 1, i * 8),
      io.IN_storeBufferBypassResp.data((i + 1) * 8 - 1, i * 8)
    )
  }
  val finalData = {
    val data = Wire(Vec(4, UInt(8.W)))
    for(i <- 0 until 4) {
      data(i) := Mux(
        bypassDataMask(i),
        bypassData(i),
        cacheLoadData((i + 1) * 8 - 1, i * 8)
      )
    }
    data
  }

  val stage1LoadHit = (cacheHit || bypassDataHit) && !loadDiscard && !bypassDataNotReady

  hitLoadResult.data := finalData.asUInt
  hitLoadResult.ready := false.B
  hitLoadResult.bypassMask := bypassDataMask | ~stage(1).mask
  hitLoadResult.addr := stage(1).addr
  hitLoadResult.opcode := stage(1).opcode
  hitLoadResult.prd := stage(1).prd
  hitLoadResult.robPtr := stage(1).robPtr
  hitLoadResult.dest := stage(1).dest

  loadResult := hitLoadResult

  io.OUT_wakeUp.valid := stageValid(1) && LSUOp.isLoad(stage(1).opcode) && stage1LoadHit
  io.OUT_wakeUp.bits := DontCare
  io.OUT_wakeUp.bits.prd := stage(1).prd

  cacheUopReq.index := stage(1).addr(log2Up(CACHE_LINE_B) + log2Up(DCACHE_SETS) - 1, log2Up(CACHE_LINE_B))
  cacheUopReq.rtag := stage(1).addr(XLEN - 1, XLEN - 1 - DCACHE_TAG + 1)
  cacheUopReq.wtag := replaceTag
  cacheUopReq.way := replaceWay
  cacheUopReq.wmask := DontCare
  cacheUopReq.wdata := DontCare
  cacheUopReq.offset := stage(1).addr(log2Up(CACHE_LINE_B) - 1, 0)
  cacheUopReq.opcode := replaceOpcode
  
  when(stageValid(1)) {
    when(LSUOp.isLoad(stage(1).opcode)) {
      when(loadDiscard) {

      }.otherwise {
        // * Find out if we can serve cacheUop
        cacheUopReqValid := !stage1LoadHit && needCacheUop && !bypassDataNotReady
        hitLoadResultValid := stage1LoadHit

        // * LoadResult: from Stage(0)
        when(!stage1LoadHit) {
          when(!(loadResultBuffer.io.OUT_numEmpty > 1.U) || !canServeCacheUop || !needCacheUop || bypassDataNotReady) {
            // * NegAck
            loadNegAckValid := true.B
            loadNegAck.dest := stage(1).dest
            loadNegAck.ldqPtr := stage(1).ldqPtr
          }.otherwise {
            when(!io.IN_flush || stage(1).dest === Dest.PTW) {
              loadResultValid := true.B
            }
          }
        }
      }
    }.otherwise {
      // * Store
      cacheUopReqValid := cacheMiss && needCacheUop
    }
  }

  when(loadResultValid) {
    loadResultBuffer.io.IN_loadResult.valid := loadResultValid
    loadResultBuffer.io.IN_loadResult.bits := loadResult
  }.otherwise {
    // * Serve UncachedLSU
    loadResultBuffer.io.IN_loadResult <> uncachedLSU.io.OUT_loadResult
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
    amoWriteback.data := !scFail
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
      // ! For writeTag, see LoadPipeline situation
      val amoLoadFailed = writeTag || !amoTagHit || amoAddrAlreadyInFlight
      val amoNeedLoad = !writeTag && !amoTagHit && !amoAddrAlreadyInFlight
      val amoUopIndex = getDCacheIndex(amoUopReg.addr)
      cacheUopReq.index := amoUopIndex
      cacheUopReq.rtag := amoUopReg.addr(XLEN - 1, XLEN - 1 - DCACHE_TAG + 1)
      cacheUopReq.wtag := tagResp(replaceCounter).tag
      cacheUopReq.way := replaceCounter
      cacheUopReq.wmask := 0.U
      cacheUopReq.wdata := 0.U
      cacheUopReq.opcode := Mux(tagResp(replaceCounter).valid, CacheOpcode.REPLACE, CacheOpcode.LOAD)
      cacheUopReq.offset := amoUopReg.addr(log2Up(CACHE_LINE_B) - 1, 0)
      when(amoLoadFailed) {
        // * Amo Neg Ack
        amoAckValid := true.B
        amoAck.resp := 1.U
        when(amoNeedLoad) {
          // * Cache Controller
          amoNeedCacheUop := true.B
          cacheUopReqValid := true.B
        }
      }

      amoHitWayReg := amoTagHitWay
      val amoHitCacheline = Mux1H(amoTagHitOH, dataResp)
      amoLoadData := amoHitCacheline(amoUopReg.addr(log2Up(CACHE_LINE_B) - 1, 2))
      amoState := Mux(!amoLoadFailed, Mux(amoIsSc, sAmoStore, Mux(amoIsLr, sAmoWriteback, sAmoALU)), sAmoIdle)
    }
    is(sAmoALU) {
      amoState := sAmoStore
    }
    is(sAmoStore) {
      val offset = amoUopReg.addr(log2Up(CACHE_LINE_B) - 1, 2)
      io.OUT_dataWrite.valid := true.B
      io.OUT_dataWrite.bits.addr := amoUopReg.addr
      io.OUT_dataWrite.bits.write := true.B
      io.OUT_dataWrite.bits.way := amoHitWayReg
      io.OUT_dataWrite.bits.wmask := "b1111".U << (offset * 4.U)
      io.OUT_dataWrite.bits.data := Mux(amoIsSc, amoUopReg.wdata, amoStoreData) << (offset * 32.U)

      amoSuccess := io.OUT_dataWrite.ready
      
      when(!amoSuccess) {
        amoAckValid := true.B
        amoAck.resp := 1.U
      }.otherwise {
        amoWriteData := true.B
      }

      amoState := Mux(amoSuccess, sAmoWriteback, sAmoIdle)
    }
    is(sAmoWriteback) {
      when(amoCanWriteback) {
        amoAckValid := true.B
        amoAck.resp := 0.U
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
    cacheCtrlUopValid := cacheUopReqValid
    cacheCtrlUop := cacheUopReq
  }

  // * Flush State Machine
  switch(flushState) {
    is(sFlushIdle) {
      when(io.IN_flushDCache) {
        flushState := sFlushWaitStart
        flushIndex := 0.U
        flushWay := 0.U
        flushNeedWriteback := true.B
      }
    }
    is(sFlushActive) {
      when(flushNeedWriteback) {
        cacheCtrlUopValid := tagResp(flushWay).valid
        cacheCtrlUop.opcode := CacheOpcode.INVALIDATE
        cacheCtrlUop.index := flushIndex
        cacheCtrlUop.way := flushWay
        cacheCtrlUop.rtag := 0.U
        cacheCtrlUop.wtag := tagResp(flushWay).tag
        cacheCtrlUop.cacheId := CacheId.DCACHE
      }
      flushState := Mux(flushNeedWriteback, sFlushWriteTag, sFlushActive)
      when(flushIndex === (DCACHE_SETS - 1).U) {        
        when(flushWay === (DCACHE_WAYS - 1).U) {
          flushState := Mux(flushNeedWriteback, sFlushWaitEnd, sFlushIdle)
        }.otherwise {
          flushIndex := 0.U
          flushWay := flushWay + 1.U
        }
      }.otherwise {
        flushIndex := flushIndex + 1.U
      }
    }
    is(sFlushWaitStart) {
      // * Wait for all MSHR & cacheCtrlUop to be idle
      val hasActiveMSHR = io.IN_mshrs.map(_.valid).reduce(_ || _)
      
      when(!hasActiveMSHR && !io.OUT_cacheCtrlUop.valid) {
        flushState := sFlushRead
      }
    }
    is(sFlushWriteTag) {
      // * sFlushActive Writes Tag on this cycle
      flushState := sFlushRead
    }
    is(sFlushRead) {
      // * Read tag
      when(!io.OUT_cacheCtrlUop.valid || io.OUT_cacheCtrlUop.ready) {
        // * Wait until we can send a new cacheCtrlUop
        flushState := sFlushActive
      }
    }
    is(sFlushWaitEnd) {
      // * Wait for all MSHR & cacheCtrlUop to be idle
      val hasActiveMSHR = io.IN_mshrs.map(_.valid).reduce(_ || _)
      
      when(!hasActiveMSHR && !io.OUT_cacheCtrlUop.valid) {
        flushState := sFlushIdle
        flushNeedWriteback := false.B
      }
    }
  }

  io.OUT_loadNegAck.valid := loadNegAckValid
  io.OUT_loadNegAck.bits := loadNegAck

  io.OUT_storeAck.valid := storeAckValid
  io.OUT_storeAck.bits := storeAck

  io.OUT_amoAck.valid := amoAckValid
  io.OUT_amoAck.bits := amoAck
  
  io.OUT_busy := stageValid(0) || stageValid(1) || cacheCtrlUopValid

  // * Output 
  when(loadResultBuffer.io.OUT_writebackUop.valid) {
    io.OUT_writebackUop := loadResultBuffer.io.OUT_writebackUop
  }.otherwise {
    amoCanWriteback := true.B
    io.OUT_writebackUop.valid := amoState === sAmoWriteback
    io.OUT_writebackUop.bits := amoWriteback
  }

  when(io.IN_flush) {
    when(LSUOp.isLoad(stage(0).opcode) && stage(0).dest =/= Dest.PTW) {
      stageValid(1) := false.B
    }
    when(LSUOp.isLoad(stage(1).opcode) && stage(1).dest =/= Dest.PTW) {
      loadResultValid := false.B
    }
  }
}