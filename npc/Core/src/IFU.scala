import chisel3._
import chisel3.util._
import chisel3.SpecifiedDirection.Flip
import utils._

class FetchGroup extends CoreBundle {
  val pc = UInt(XLEN.W)
  val brTaken = Bool()
  val brOffset = UInt(log2Up(FETCH_WIDTH).W)
  val brTarget = UInt(XLEN.W)
  val insts = Vec(FETCH_WIDTH, UInt(32.W))
  val pageFault = Bool()
  val interrupt = Bool()
  val access_fault = Bool()
}

class IFUIO extends CoreBundle {
  // * Invalidate whole ICache
  val flushICache = Input(Bool())
  // * Redirect PC
  val redirect = Input(new RedirectSignal)
  // * BPU update
  val IN_btbUpdate = Flipped(Valid(new BTBUpdate))
  val IN_phtUpdate = Flipped(Valid(new PHTUpdate))

  // * Output, inst + pc
  val out = Vec(FETCH_WIDTH, Valid(new InstSignal))
  val IN_ready = Flipped(Bool())

  // * ICache Interface 
  val OUT_ITagRead = Valid(new ITagRead)
  val OUT_ITagWrite = Valid(new ITagWrite)
  val IN_ITagResp = Flipped(new ITagResp)

  val OUT_IDataRead = Valid(new IDataRead)
  val IN_IDataResp = Flipped(new IDataResp)

  // * Cache Controller Interface
  val OUT_cacheCtrlUop = Decoupled(new CacheCtrlUop)

  // * MSHR 
  val IN_mshrs = Flipped(Vec(NUM_MSHR, new MSHR))

  //* VM
  val OUT_TLBReq = Valid(new TLBReq)
  val IN_TLBResp = Flipped(Valid(new TLBResp))
  val OUT_PTWReq = Decoupled(new PTWReq)
  val IN_PTWResp = Flipped(Valid(new PTWResp))
  val IN_VMCSR   = Flipped(new VMCSR)
  val IN_trapCSR = Flipped(new TrapCSR)

  // * PC
  val OUT_vPC = UInt(XLEN.W)
  val OUT_phyPC = UInt(XLEN.W)
  val OUT_fixRedirect = new RedirectSignal
  val OUT_fetchRedirect = new RedirectSignal
  val OUT_fetchCanContinue = Bool()
  val OUT_vPCNext = UInt(XLEN.W)
  val OUT_prediction = new Prediction
  val OUT_vPCNextValid = Bool()
  val OUT_fetchGroup = new FetchGroup
  val OUT_phyPCValid = Bool()
  val OUT_cacheMiss = Bool()
}

// * PTW Request id = 0
class IFU extends Module with HasPerfCounters with HasCoreParameters {
  val io = IO(new IFUIO)

  val bpu0 = Module(new BPU0)
  val fixBranch = Module(new FixBranch)

  bpu0.io.IN_btbUpdate := io.IN_btbUpdate
  bpu0.io.IN_phtUpdate := io.IN_phtUpdate
  bpu0.io.IN_fixBTBUpdate := fixBranch.io.OUT_btbUpdate

  val fetchBuffer = Module(new FetchBuffer)
  val instAligner = Module(new InstAligner)

  val replaceCounter = RegInit(0.U(2.W))
  replaceCounter := Mux(replaceCounter === ICACHE_WAYS.U - 1.U, 0.U, replaceCounter + 1.U)

  val vPC      = RegInit(UInt(XLEN.W), Config.resetPC)
  // * Static Next vPC
  val snVPC = Cat(vPC(XLEN - 1, log2Ceil(FETCH_WIDTH * 4)) + 1.U, 0.U(log2Ceil(FETCH_WIDTH * 4).W))
  val vPCNext  = Wire(UInt(XLEN.W))

  val phyPCNext      = Wire(UInt(XLEN.W))
  val phyPCValidNext = WireInit(false.B)

  val phyPC       = Reg(UInt(XLEN.W))
  val phyPCValid  = RegInit(false.B)
  val vPC1        = Reg(UInt(XLEN.W))  
  val prediction  = Reg(new Prediction)
  val pageFault   = Reg(Bool())
  val interrupt   = Reg(Bool())
  val flushBuffer = RegInit(false.B)

  val accessFault = !Addr.isMainMem(phyPC)

  // !
  io.OUT_vPC := vPC
  io.OUT_phyPC := phyPC
  io.OUT_fetchCanContinue := fetchBuffer.io.fetchCanContinue
  io.OUT_vPCNext := vPCNext
  io.OUT_vPCNextValid := phyPCValidNext
  io.OUT_prediction := prediction
  // !

  val cacheMiss = WireInit(false.B)
  val needCacheOp = WireInit(false.B)
  val canServeCacheUop = Wire(Bool())
  val cacheCtrlUopNext = WireInit(0.U.asTypeOf(new CacheCtrlUop))
  
  val fetchValid = WireInit(false.B)
  val fetchGroup = Wire(new FetchGroup)
  // !
  io.OUT_fetchGroup := fetchGroup
  io.OUT_phyPCValid := phyPCValid
  io.OUT_cacheMiss := cacheMiss
  // !
  dontTouch(fetchGroup)
  dontTouch(fetchValid)
  fixBranch.io.IN_fetchGroup.valid := fetchValid
  fixBranch.io.IN_fetchGroup.bits := fetchGroup
  fixBranch.io.IN_prediction := prediction

  val cacheCtrlUop = Reg(new CacheCtrlUop)
  val cacheCtrlUopValid = RegInit(false.B)

  // * ICache Flush State Machine
  val sFlushIdle :: sFlushActive :: Nil = Enum(2)
  val flushState = RegInit(sFlushActive)
  val flushIndex = RegInit(0.U(log2Up(ICACHE_SETS).W))
  val flushWay   = RegInit(0.U(log2Up(ICACHE_WAYS).W))
  switch(sFlushActive) {
    is(sFlushIdle) {
      when(io.flushICache) {
        flushState := sFlushActive
        flushIndex := 0.U
        flushWay := 0.U
      }
    }
    is(sFlushActive) {
      when(flushIndex === (ICACHE_SETS - 1).U) {        
        when(flushWay === (ICACHE_WAYS - 1).U) {
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


  // * ICache miss replay as a redirect
  val fetchRedirect = Wire(new RedirectSignal)
  // * Fix Branch generated redirect
  val fixRedirect = Wire(new RedirectSignal)  
  dontTouch(fixRedirect)
  fixRedirect := fixBranch.io.OUT_redirect

  // !
  io.OUT_fetchRedirect := fetchRedirect
  io.OUT_fixRedirect := fixRedirect
  // !

  val redirect = Mux(io.redirect.valid, 
                    io.redirect, 
                    Mux(fixRedirect.valid, fixRedirect, fetchRedirect))

  // * Stage 0: PC addr translation
  // ** Branch Prediction
  bpu0.io.IN_pcNext := vPCNext
  bpu0.io.IN_pc := vPC
  vPCNext := Mux(redirect.valid, redirect.pc, 
                Mux(bpu0.io.OUT_prediction.brTaken, bpu0.io.OUT_prediction.btbTarget, snVPC))

  // ** vpc -> (pcNext, pcValidNext) => (pc, arValid/validBuffer)
  val doTranslate = io.IN_VMCSR.mode === 1.U && io.IN_VMCSR.priv < Priv.M
  io.OUT_TLBReq.valid := doTranslate
  io.OUT_TLBReq.bits.vpn := vPC(31, 12)
  phyPCValidNext := (!doTranslate || io.IN_TLBResp.valid) && 
                    fetchBuffer.io.fetchCanContinue && 
                    flushState === sFlushIdle
  phyPCNext := Mux(doTranslate, io.IN_TLBResp.bits.vaddrToPaddr(vPC), vPC)    

  val dataResp = WireInit(io.IN_IDataResp.data)
  io.OUT_ITagWrite := 0.U.asTypeOf(io.OUT_ITagWrite)

  // * Stage 0: ICache access 
  // ** Tag
  io.OUT_ITagRead.valid := true.B
  io.OUT_ITagRead.bits.addr := vPC
  // ** Data
  io.OUT_IDataRead.valid := true.B
  io.OUT_IDataRead.bits.addr := vPC

  // * Stage 1: Translation check / ICache hit check
  val tagHitVec = VecInit(io.IN_ITagResp.tags.map(e => e.valid && e.tag === phyPC(XLEN - 1, XLEN - ICACHE_TAG)))
  val tagHitWay = OHToUInt(tagHitVec)
  val tagHit = tagHitVec.reduce(_ || _)

  when(phyPCValid) {
    val alreadyInFlight = MSHRChecker.isLoadAddrAlreadyInFlight(io.IN_mshrs, io.OUT_cacheCtrlUop, CacheId.ICACHE, phyPC)
    cacheMiss := !tagHit || alreadyInFlight
    when(cacheMiss && !alreadyInFlight && !accessFault) {
      needCacheOp := true.B
      cacheCtrlUopNext.cacheId := CacheId.ICACHE
      cacheCtrlUopNext.rtag := phyPC(XLEN - 1, XLEN - ICACHE_TAG)
      cacheCtrlUopNext.wtag := 0.U
      cacheCtrlUopNext.wmask := 0.U
      cacheCtrlUopNext.offset := phyPC(log2Up(CACHE_LINE_B) - 1, 0)
      cacheCtrlUopNext.wdata := 0.U
      cacheCtrlUopNext.opcode := CacheOpcode.LOAD
      cacheCtrlUopNext.index := phyPC(log2Up(CACHE_LINE_B) + log2Up(ICACHE_SETS) - 1, log2Up(CACHE_LINE_B))
      cacheCtrlUopNext.way := replaceCounter
    }
  }

  // * redirect / vPC generation
  when(redirect.valid) {
    phyPCValid := false.B
    pageFault := false.B
    interrupt := false.B
  }.otherwise {
    phyPCValid := phyPCValidNext
    pageFault := doTranslate && io.IN_TLBResp.valid && io.IN_TLBResp.bits.executePermFail(io.IN_VMCSR)
    interrupt := io.IN_trapCSR.interrupt
  }
  vPC := Mux(phyPCValidNext || redirect.valid, vPCNext, vPC)
  phyPC := phyPCNext
  prediction := bpu0.io.OUT_prediction
  vPC1 := vPC
  
  // ** Fetch redirect
  fetchRedirect.valid := phyPCValid && cacheMiss
  fetchRedirect.pc := vPC1
  dontTouch(redirect)
  dontTouch(fetchRedirect)

  // ** Fetch Buffer
  when(io.redirect.valid) {
    fetchValid := false.B
  }.otherwise {   
    fetchValid := phyPCValid && (!cacheMiss || pageFault || accessFault)
  }
  val fetchGroupIndex = if(FETCH_WIDTH == CACHE_LINE_B / 4) 0.U else phyPC(log2Up(CACHE_LINE_B) - 1, log2Ceil(FETCH_WIDTH) + 2)
  val fetchGroups = Wire(Vec((CACHE_LINE_B / 4) /FETCH_WIDTH, Vec(FETCH_WIDTH, UInt(32.W))))
  fetchGroups := dataResp(tagHitWay).asTypeOf(fetchGroups)
  fetchGroup.pc := vPC1
  fetchGroup.brTaken := prediction.brTaken
  fetchGroup.brOffset := prediction.brOffset
  fetchGroup.brTarget := prediction.btbTarget
  fetchGroup.insts := fetchGroups(fetchGroupIndex)
  fetchGroup.interrupt := interrupt
  fetchGroup.pageFault := pageFault
  fetchGroup.access_fault := accessFault

  val fixedFetchGroup = WireInit(fetchGroup)
  dontTouch(fixedFetchGroup)
  when(fixBranch.io.OUT_fixBrOffsetValid) {
    fixedFetchGroup.brOffset := fixBranch.io.OUT_fixBrOffset
    fixedFetchGroup.brTaken := fixRedirect.valid
    fixedFetchGroup.brTarget := fixRedirect.pc
  }

  fetchBuffer.io.flush := io.redirect.valid
  fetchBuffer.io.in.valid := fetchValid
  fetchBuffer.io.in.bits := fixedFetchGroup

  // ** Inst Aligner
  instAligner.io.IN_fetchGroup <> fetchBuffer.io.out
  instAligner.io.IN_ready <> io.IN_ready
  instAligner.io.OUT_insts <> io.out
  instAligner.io.IN_flush := io.redirect.valid

  // ** Write Tag
  val ITagWrite = Wire(Valid(new ITagWrite))
  io.OUT_ITagWrite := ITagWrite
  when(flushState === sFlushActive) {
    ITagWrite.valid := true.B
    ITagWrite.bits.addr := Cat(flushIndex, 0.U(log2Ceil(CACHE_LINE_B).W))
    ITagWrite.bits.way := flushWay
    ITagWrite.bits.data.valid := false.B
    ITagWrite.bits.data.tag := 0.U
  }.otherwise {
    ITagWrite.valid := needCacheOp && canServeCacheUop
    ITagWrite.bits.addr := phyPC
    ITagWrite.bits.way := replaceCounter
    ITagWrite.bits.data.valid := true.B
    ITagWrite.bits.data.tag := phyPC(XLEN - 1, XLEN - ICACHE_TAG)
  }


  // ** PTW Req logic
  val ptwReqValid = RegInit(false.B)
  val ptwReq      = Reg(new PTWReq)

  ptwReqValid := doTranslate && !io.IN_TLBResp.valid
  when(io.IN_PTWResp.valid && io.IN_PTWResp.bits.id === 0.U) {
    ptwReqValid := false.B
  }
  ptwReq.vpn := vPC(31, 12)

  io.OUT_PTWReq.valid := ptwReqValid
  io.OUT_PTWReq.bits  := ptwReq 

  // ** CacheCtrlUop
  io.OUT_cacheCtrlUop.valid := cacheCtrlUopValid
  io.OUT_cacheCtrlUop.bits := cacheCtrlUop
  val conflict = MSHRChecker.conflict(io.IN_mshrs, io.OUT_cacheCtrlUop, cacheCtrlUopNext)
  dontTouch(conflict)
  canServeCacheUop := (!cacheCtrlUopValid || io.OUT_cacheCtrlUop.fire) &&
    !conflict
  when (io.OUT_cacheCtrlUop.fire) {
    cacheCtrlUopValid := false.B
  }
  when (needCacheOp && canServeCacheUop) {
    cacheCtrlUopValid := true.B
    cacheCtrlUop := cacheCtrlUopNext
  }
  dontTouch(needCacheOp)
  dontTouch(canServeCacheUop)

  monitorEvent(ifuFinished, io.out(0).fire)
  monitorEvent(ifuStalled, io.out(0).valid && ~reset.asBool)
}

class FetchCacheLine extends CoreModule {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt(32.W)))
    val out = new Bundle {
      val offset = UInt(2.W)
      val data   = UInt(32.W)
      val valid  = Bool()
      val fin    = Bool()
    }
    val master = new AXI4(AXI_DATA_WIDTH, AXI_ADDR_WIDTH)
  })

  val data = Reg(Vec(4, UInt(32.W)))

  val rCnt = RegInit(0.U(3.W))

  rCnt := Mux(io.in.fire, 0.U, Mux(io.master.r.fire, rCnt + 1.U, rCnt))

  val sIdle :: sFill :: Nil = Enum(2)
  val state                 = RegInit(sIdle)
  state := MuxLookup(state, sIdle)(
    Seq(
      sIdle -> Mux(io.in.valid, sFill, sIdle),
      sFill -> Mux(io.out.fin, sIdle, sFill)
    )
  )

  // val isBurst = io.in.bits >= "hA0000000".U && io.in.bits < "hA2000000".U
  val isBurst = false.B

  val lowAddrBuffer = Reg(UInt(4.W))
  val nextLowAddr   = Mux(!isBurst && io.master.r.valid, lowAddrBuffer + 4.U, lowAddrBuffer)
  lowAddrBuffer := Mux(io.in.fire, 0.U(4.W), nextLowAddr)
  val addr = Cat(io.in.bits(31, 4), lowAddrBuffer)

  val reqValid = Reg(Bool())
  reqValid := Mux(
    io.in.fire,
    true.B,
    Mux(
      io.master.ar.fire,
      false.B,
      Mux(!isBurst && io.master.r.fire, rCnt < 3.U, reqValid)
    )
  )
  io.master.ar.valid      := reqValid && ~reset.asBool
  io.master.ar.bits.addr  := addr
  io.master.ar.bits.id    := 0.U
  io.master.ar.bits.len   := Mux(isBurst, 3.U, 0.U)
  io.master.ar.bits.size  := 2.U
  io.master.ar.bits.burst := "b01".U

  io.master.r.ready := true.B

  io.master.aw.valid      := false.B
  io.master.aw.bits.addr  := 0.U
  io.master.aw.bits.id    := 0.U
  io.master.aw.bits.len   := 0.U
  io.master.aw.bits.size  := 0.U
  io.master.aw.bits.burst := "b01".U

  io.master.w.valid     := false.B
  io.master.w.bits.data := 0.U
  io.master.w.bits.strb := 0.U
  io.master.w.bits.last := false.B

  io.master.b.ready := false.B

  io.out.offset := rCnt
  val readDataVec = Wire(Vec(8, UInt(32.W)))
  val idx = Cat(addr(4), rCnt(1, 0))
  dontTouch(idx)
  readDataVec := io.master.r.bits.data.asTypeOf(readDataVec)
  io.out.data   := readDataVec(idx)
  io.out.valid  := io.master.r.valid && state === sFill
  io.out.fin    := state === sFill && rCnt === 4.U
  io.in.ready   := state === sIdle
}

class ICache extends CoreModule with HasPerfCounters {
  val io = IO(new Bundle {
    val in          = Flipped(Decoupled(UInt(32.W)))
    val out         = Decoupled(UInt(32.W))
    val flushICache = Input(Bool())
    val master      = new AXI4(AXI_DATA_WIDTH, AXI_ADDR_WIDTH)
  })
  val numCacheLine = 4

  val validBuffer = RegInit(false.B)

  val data     = Reg(Vec(numCacheLine, Vec(4, UInt(32.W))))
  val tag      = Reg(Vec(numCacheLine, UInt(26.W)))
  val valid    = RegInit(VecInit(Seq.fill(numCacheLine)(false.B)))
  val inTag    = io.in.bits(31, 6)
  val inOffset = io.in.bits(3, 2)
  val inIndex  = io.in.bits(5, 4)

  val fetchLine = Module(new FetchCacheLine)
  io.master <> fetchLine.io.master

  val sIdle :: sFetchReq :: sFetchWaitReply :: Nil = Enum(3)

  val state = RegInit(sIdle)

  fetchLine.io.in.bits  := io.in.bits
  fetchLine.io.in.valid := state === sFetchReq

  val hitMap = valid.zip(tag).map { case (v, t) => v && t === inTag }
  // val hit    = hitMap.reduce(_ || _)
  val hit = valid(inIndex) && tag(inIndex) === inTag

  state := MuxLookup(state, sIdle)(
    Seq(
      sIdle -> Mux(io.in.fire, Mux(hit, sIdle, sFetchReq), sIdle),
      sFetchReq -> Mux(fetchLine.io.in.fire, sFetchWaitReply, sFetchReq),
      sFetchWaitReply -> Mux(io.out.fire, sIdle, sFetchWaitReply)
    )
  )

  when(state === sIdle && io.in.valid) {
    validBuffer := true.B
  }
  when(io.out.fire) {
    validBuffer := false.B
  }

  when(io.flushICache) {
    valid.foreach(_ := false.B)
  }

  val replaceIdx = RegInit(0.U(2.W))
  replaceIdx := Mux(replaceIdx === 2.U, replaceIdx, replaceIdx + 1.U)

  when(fetchLine.io.out.valid) {
    data(inIndex)(fetchLine.io.out.offset) := fetchLine.io.out.data
  }
  when(fetchLine.io.out.fin) {
    tag(inIndex)   := inTag
    valid(inIndex) := true.B
  }

  io.in.ready := state === sIdle && !io.flushICache

  val line = Mux1H(hitMap, data)
  // io.out.bits := line(inOffset)
  io.out.bits := data(inIndex)(inOffset)

  io.out.valid := (io.in.valid || validBuffer) && hit

  monitorEvent(icacheMiss, state === sIdle && !hit)
}

class FullyAssocICache extends CoreModule with HasPerfCounters {
  val io = IO(new Bundle {
    val in     = Flipped(Decoupled(UInt(32.W)))
    val out    = Decoupled(UInt(32.W))
    val master = new AXI4(AXI_DATA_WIDTH, AXI_ADDR_WIDTH)
  })
  val numCacheLine = 3

  val data     = Reg(Vec(numCacheLine, Vec(4, UInt(32.W))))
  val tag      = Reg(Vec(numCacheLine, UInt(28.W)))
  val valid    = RegInit(VecInit(Seq.fill(numCacheLine)(false.B)))
  val inTag    = io.in.bits(31, 4)
  val inOffset = io.in.bits(3, 2)
  val inIndex  = io.in.bits(5, 4)

  val fetchLine = Module(new FetchCacheLine)
  io.master <> fetchLine.io.master

  val sIdle :: sCheckHit :: sFetchLine :: Nil = Enum(3)

  val state = RegInit(sIdle)

  fetchLine.io.in.bits  := io.in.bits
  fetchLine.io.in.valid := state === sFetchLine

  val hitMap = valid.zip(tag).map { case (v, t) => v && t === inTag }
  val hit    = hitMap.reduce(_ || _)

  state := MuxLookup(state, sIdle)(
    Seq(
      sIdle -> Mux(io.in.valid, sCheckHit, sIdle),
      sCheckHit -> Mux(hit, Mux(io.out.fire, sIdle, sCheckHit), sFetchLine),
      sFetchLine -> Mux(fetchLine.io.out.fin, sCheckHit, sFetchLine)
    )
  )

  val hasInvalid = !valid.reduce(_ && _)
  val invalidIdx = PriorityMux(valid.zipWithIndex.map { case (v, i) => (!v -> i.U) })
  val rndIdxReg  = RegInit(0.U(2.W))
  val rndIdx     = RegEnable(rndIdxReg, state === sCheckHit && !hit)
  val replaceIdx = Mux(hasInvalid, invalidIdx, rndIdx)
  rndIdxReg := Mux(rndIdxReg === numCacheLine.U - 1.U, 0.U, rndIdxReg + 1.U)

  when(fetchLine.io.out.valid) {
    data(replaceIdx)(fetchLine.io.out.offset) := fetchLine.io.out.data
  }
  when(fetchLine.io.out.fin) {
    tag(replaceIdx)   := inTag
    valid(replaceIdx) := true.B
  }

  io.in.ready := state === sIdle

  io.out.bits := Mux1H(hitMap, data)(inOffset)

  io.out.valid := state === sCheckHit && hit

  monitorEvent(icacheMiss, state === sCheckHit && !hit)
}

// class ICacheTest extends Module {
//   val io = IO(new Bundle {
//     val in  = Input(UInt(32.W))
//     val out = UInt(32.W)
//   })

//   val x      = Reg(UInt(32.W))
//   val icache = Module(new ICache)
//   icache.io.in    := x ^ io.in
//   x               := icache.io.out
//   icache.io.wdata := x & io.in
//   icache.io.win   := x | io.in
//   io.out          := x
// }
