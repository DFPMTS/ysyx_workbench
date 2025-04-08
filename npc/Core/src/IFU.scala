import chisel3._
import chisel3.util._
import chisel3.SpecifiedDirection.Flip
import utils._

class IFUIO extends CoreBundle {
  // * Invalidate whole ICache
  val flushICache = Input(Bool())
  // * Redirect PC
  val redirect = Input(new RedirectSignal)
  // * Output, inst + pc
  val out = Decoupled(new InstSignal)

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
}

// * PTW Request id = 0
class IFU extends Module with HasPerfCounters with HasCoreParameters {
  val io = IO(new IFUIO)

  val instBuffer = Module(new InstBuffer)

  val replaceCounter = RegInit(0.U(2.W))
  replaceCounter := Mux(replaceCounter === DCACHE_WAYS.U - 1.U, 0.U, replaceCounter + 1.U)

  val vPC      = RegInit(UInt(XLEN.W), Config.resetPC)
  // * Static Next vPC
  val vPCNext  = WireInit(Cat(vPC(XLEN - 1, log2Up(FETCH_WIDTH * 4)) + 1.U, 0.U(log2Up(FETCH_WIDTH * 4).W)))

  val phyPCNext      = Wire(UInt(XLEN.W))
  val phyPCValidNext = WireInit(false.B)

  val phyPC       = Reg(UInt(XLEN.W))
  val phyPCValid  = RegInit(false.B)
  val vPC1        = Reg(UInt(XLEN.W))
  val pageFault   = Reg(Bool())
  val interrupt   = Reg(Bool())
  val flushBuffer = RegInit(false.B)

  val cacheMiss = WireInit(false.B)
  val needCacheOp = WireInit(false.B)
  val canServeCacheUop = Wire(Bool())
  val cacheCtrlUopNext = WireInit(0.U.asTypeOf(new CacheCtrlUop))
  
  val outValid = RegInit(false.B)
  val outInst = Reg(new InstSignal)

  val cacheCtrlUop = Reg(new CacheCtrlUop)
  val cacheCtrlUopValid = RegInit(false.B)

  // * ICache miss replay as a redirect
  val fetchRedirect = Wire(new RedirectSignal)
  
  val redirect = Mux(io.redirect.valid, io.redirect, fetchRedirect)

  // * Stage 0: PC addr translation
  // ** vpc -> (pcNext, pcValidNext) => (pc, arValid/validBuffer)
  val doTranslate = io.IN_VMCSR.mode === 1.U && io.IN_VMCSR.priv < Priv.M
  io.OUT_TLBReq.valid := doTranslate
  io.OUT_TLBReq.bits.vpn := vPC(31, 12)
  phyPCValidNext := (!doTranslate || io.IN_TLBResp.valid) && instBuffer.io.fetchCanContinue
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
    when(cacheMiss && !alreadyInFlight) {
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
    vPC := redirect.pc
    outValid := redirect.valid
    phyPCValid := false.B
    pageFault := false.B
    interrupt := false.B
  }.otherwise {
    vPC := Mux(phyPCValidNext, vPCNext, vPC)
    phyPCValid := phyPCValidNext
    pageFault := doTranslate && io.IN_TLBResp.valid && io.IN_TLBResp.bits.executePermFail(io.IN_VMCSR)
    interrupt := io.IN_trapCSR.interrupt
  }
  phyPC := phyPCNext
  vPC1 := vPC
  
  // ** Fetch redirect
  fetchRedirect.valid := phyPCValid && cacheMiss
  fetchRedirect.pc := vPC1
  dontTouch(redirect)
  dontTouch(fetchRedirect)

  // ** Output
  when(io.redirect.valid) {
    outValid := false.B
  }.otherwise {   
    outValid := phyPCValid && !cacheMiss
  }
  outInst.pc := vPC1
  outInst.inst := dataResp(tagHitWay)(vPC1(log2Up(CACHE_LINE_B) - 1, 2))
  outInst.interrupt := interrupt
  outInst.pageFault := pageFault
  outInst.access_fault := false.B
  instBuffer.io.flush := io.redirect.valid
  instBuffer.io.in.valid := outValid
  instBuffer.io.in.bits := outInst

  io.out <> instBuffer.io.out

  // ** Write Tag
  val ITagWrite = Reg(Valid(new ITagWrite))
  io.OUT_ITagWrite := ITagWrite
  ITagWrite.valid := needCacheOp && canServeCacheUop
  ITagWrite.bits.addr := phyPC
  ITagWrite.bits.way := replaceCounter
  ITagWrite.bits.data.valid := true.B
  ITagWrite.bits.data.tag := phyPC(XLEN - 1, XLEN - ICACHE_TAG)


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

  monitorEvent(ifuFinished, io.out.fire)
  monitorEvent(ifuStalled, outValid && ~reset.asBool)
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
