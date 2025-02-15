import chisel3._
import chisel3.util._
import utils._

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
  val wmask = UInt(CACHE_LINE.W)
  val data = UInt((CACHE_LINE * 8).W)
}

class DDataResp extends CoreBundle {
  val data = Vec(DCACHE_WAYS, UInt((CACHE_LINE * 8).W))
}

class NewLSUIO extends CoreBundle {
  val IN_AGUUop = Flipped(Decoupled(new AGUUop))
  // * DCache Interface
  val OUT_tagReq = Decoupled(new DTagReq)
  val IN_tagResp = Flipped(new DTagResp)
  val OUT_dataReq = Decoupled(new DDataReq)
  val IN_dataResp = Flipped(new DDataResp)
  // * Cache Controller Interface
  val OUT_cacheCtrlUop = Decoupled(new CacheCtrlUop)
  val IN_mshrs = Flipped(Vec(1, new MSHR))
  val IN_memLoadFoward = Flipped(Valid(new MemLoadFoward))

  val OUT_writebackUop = Valid(new WritebackUop)
}

class NewLSU extends CoreModule with HasLSUOps {
  val io = IO(new NewLSUIO)

  // * Submodules
  val loadResultBuffer = Module(new LoadResultBuffer)

  io.OUT_cacheCtrlUop.valid := false.B
  io.OUT_cacheCtrlUop.bits := 0.U.asTypeOf(new CacheCtrlUop)
  io.OUT_dataReq.valid := false.B
  io.OUT_dataReq.bits := 0.U.asTypeOf(new DDataReq)
  io.OUT_tagReq.valid := false.B
  io.OUT_tagReq.bits := 0.U.asTypeOf(new DTagReq)

  // * write tag
  val writeTag = WireInit(false.B)

  // * Load Pipeline
  val loadStage = Reg(Vec(2, new AGUUop))
  val loadStageValid = RegInit(VecInit(Seq.fill(2)(false.B)))

  // * Store Pipeline
  val storeStage = Reg(Vec(2, new AGUUop))
  val storeStageValid = RegInit(VecInit(Seq.fill(2)(false.B)))

  val cacheCtrlUop = Reg(new CacheCtrlUop)
  val cacheCtrlUopValid = RegInit(false.B)  
  io.OUT_cacheCtrlUop.valid := cacheCtrlUopValid
  io.OUT_cacheCtrlUop.bits := cacheCtrlUop
  when(io.OUT_cacheCtrlUop.fire) {  
    cacheCtrlUopValid := false.B
  }

  io.IN_AGUUop.ready := !writeTag && !cacheCtrlUopValid && loadResultBuffer.io.IN_loadResult.ready && io.OUT_dataReq.ready && io.OUT_tagReq.ready

  val tagResp = io.IN_tagResp.tags(0)

  // ** Load/Store Stage 0
  val loadUop = io.IN_AGUUop.fire && LSUOp.isLoad(io.IN_AGUUop.bits.opcode)
  loadStage(0) := io.IN_AGUUop.bits
  loadStageValid(0) := loadUop

  val storeUop = io.IN_AGUUop.fire && LSUOp.isStore(io.IN_AGUUop.bits.opcode) 
  storeStage(0) := io.IN_AGUUop.bits
  storeStageValid(0) := storeUop

  when(loadUop) {
    // * Tag Request
    io.OUT_tagReq.valid := true.B
    io.OUT_tagReq.bits.addr := io.IN_AGUUop.bits.addr
    // * Data Request
    io.OUT_dataReq.valid := true.B
    io.OUT_dataReq.bits.addr := io.IN_AGUUop.bits.addr
  }.elsewhen(storeUop) {
    // * Tag Request
    io.OUT_tagReq.valid := true.B
    io.OUT_tagReq.bits.addr := io.IN_AGUUop.bits.addr
  }

  // ** Load/Store Stage 1
  
  // * Load Result
  val loadResult = WireInit(0.U.asTypeOf(new LoadResult))
  val loadResultValid = WireInit(false.B)
  loadResultBuffer.io.IN_loadResult.valid := loadResultValid
  loadResultBuffer.io.IN_loadResult.bits := loadResult
  loadResultBuffer.io.IN_memLoadFoward := io.IN_memLoadFoward
  
  // * Load cache hit or miss 
  val loadTagHit = tagResp.valid && tagResp.tag === loadStage(0).addr(XLEN - 1, XLEN - 1 - DCACHE_TAG + 1)
  val loadMSHRConflict = io.IN_mshrs(0).valid && 
    io.IN_mshrs(0).memReadAddr(XLEN - 1, log2Up(CACHE_LINE * 8)) === loadStage(0).addr(XLEN - 1, log2Up(CACHE_LINE * 8))
  val loadCacheCtrlConflict = cacheCtrlUopValid && 
    Cat(cacheCtrlUop.rtag, cacheCtrlUop.index) === loadStage(0).addr(XLEN - 1, log2Up(CACHE_LINE * 8))
  val loadHit = loadTagHit && !loadMSHRConflict && !loadCacheCtrlConflict
  // * store cache hit or miss
  val storeTagHit = tagResp.valid && tagResp.tag === storeStage(0).addr(XLEN - 1, XLEN - 1 - DCACHE_TAG + 1)
  val storeMSHRConflict = io.IN_mshrs(0).valid && 
    io.IN_mshrs(0).memReadAddr(XLEN - 1, log2Up(CACHE_LINE * 8)) === storeStage(0).addr(XLEN - 1, log2Up(CACHE_LINE * 8))
  val storeCacheCtrlConflict = cacheCtrlUopValid &&
    Cat(cacheCtrlUop.rtag, cacheCtrlUop.index) === storeStage(0).addr(XLEN - 1, log2Up(CACHE_LINE * 8))
  val storeHit = storeTagHit && !storeMSHRConflict && !storeCacheCtrlConflict

  when(loadStageValid(0)) {
    when(loadHit) {
      // * Cache Hit
    }.otherwise {
      // * Cache Miss
      // * Write tag
      writeTag := true.B
      io.OUT_tagReq.valid := true.B
      io.OUT_tagReq.bits.addr := loadStage(0).addr
      io.OUT_tagReq.bits.write := true.B
      val dtag = Wire(new DTag)
      dtag.valid := true.B
      dtag.tag := loadStage(0).addr(XLEN - 1, XLEN - 1 - DCACHE_TAG + 1)
      io.OUT_tagReq.bits.data := dtag
      // * Cache Controller
      cacheCtrlUopValid := true.B
      cacheCtrlUop.index := loadStage(0).addr(log2Up(CACHE_LINE) + log2Up(DCACHE_SETS) - 1, log2Up(CACHE_LINE))
      cacheCtrlUop.rtag := loadStage(0).addr(XLEN - 1, XLEN - 1 - DCACHE_TAG + 1)
      cacheCtrlUop.wtag := tagResp.tag
      cacheCtrlUop.wmask := 0.U
      cacheCtrlUop.wdata := 0.U
      cacheCtrlUop.opcode := Mux(tagResp.valid, CacheOpcode.REPLACE, CacheOpcode.LOAD)
    }
    loadResultValid := true.B
    loadResult.data := io.IN_dataResp.data(0)
    loadResult.ready := loadHit
    loadResult.addr := loadStage(0).addr
    loadResult.opcode := loadStage(0).opcode
    loadResult.prd := loadStage(0).prd
    loadResult.robPtr := loadStage(0).robPtr
  }.elsewhen(storeStageValid(0)) {
    val memLen = storeStage(0).opcode(2, 1)
    val addrOffset = storeStage(0).addr(log2Up(XLEN/8) - 1, 0)
    val wmask = MuxLookup(memLen, 0.U(4.W))(
      Seq(
        0.U(2.W) -> "b0001".U,
        1.U(2.W) -> "b0011".U,
        2.U(2.W) -> "b1111".U
      )
    ) << addrOffset
    when(storeHit) {
      
      // * Cache Hit - Write data
      io.OUT_dataReq.valid := true.B
      io.OUT_dataReq.bits.addr := storeStage(0).addr
      io.OUT_dataReq.bits.write := true.B
      io.OUT_dataReq.bits.wmask := wmask
      io.OUT_dataReq.bits.data := storeStage(0).wdata << (storeStage(0).addr(log2Up(CACHE_LINE) - 1, 0) << 3)
    }.otherwise {
      // * Cache Miss
      // * Write tag
      writeTag := true.B
      io.OUT_tagReq.valid := true.B
      io.OUT_tagReq.bits.addr := storeStage(0).addr
      io.OUT_tagReq.bits.write := true.B
      val dtag = Wire(new DTag)
      dtag.valid := true.B
      dtag.tag := storeStage(0).addr(XLEN - 1, XLEN - 1 - DCACHE_TAG + 1)
      io.OUT_tagReq.bits.data := dtag
      // * Cache Controller
      cacheCtrlUopValid := true.B  
      cacheCtrlUop.index := storeStage(0).addr(log2Up(CACHE_LINE) + log2Up(DCACHE_SETS) - 1, log2Up(CACHE_LINE))
      cacheCtrlUop.rtag := storeStage(0).addr(XLEN - 1, XLEN - 1 - DCACHE_TAG + 1)
      cacheCtrlUop.wtag := tagResp.tag
      cacheCtrlUop.wdata := storeStage(0).wdata
      cacheCtrlUop.wmask := wmask
      cacheCtrlUop.opcode := Mux(tagResp.valid, CacheOpcode.REPLACE, CacheOpcode.LOAD)
    }
  }

  // * Output 
  io.OUT_writebackUop <> loadResultBuffer.io.OUT_writebackUop
}

class LoadResult extends CoreBundle{
  val data = UInt(XLEN.W)
  val ready = Bool()
  // * addr
  val addr = UInt(XLEN.W)
  val opcode = UInt(OpcodeWidth.W)
  val prd = UInt(PREG_IDX_W)
  val robPtr = RingBufferPtr(ROB_SIZE)
}

class LoadResultBufferIO extends CoreBundle {
  val IN_loadResult = Flipped(Decoupled(new LoadResult))
  val IN_memLoadFoward = Flipped(Valid(new MemLoadFoward))
  val OUT_writebackUop = Valid(new WritebackUop)
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

  // Accept new load if there's space
  when(io.IN_loadResult.fire) {
    valid(allocPtr) := true.B
    entries(allocPtr) := io.IN_loadResult.bits
  }
  
  // Forward load data to all entries
  for (i <- 0 until N) {
    when(io.IN_memLoadFoward.valid && 
          io.IN_memLoadFoward.bits.addr(XLEN - 1, log2Up(AXI_DATA_WIDTH - 1)) === entries(i).addr(XLEN - 1, log2Up(AXI_DATA_WIDTH - 1))) {
      val addrOffset = entries(i).addr(log2Up(AXI_DATA_WIDTH/8) - 1, 0)
      val rawData = io.IN_memLoadFoward.bits.data >> (addrOffset << 3)
      val loadU = entries(i).opcode(0)
      val memLen = entries(i).opcode(2,1)
      entries(i).data := MuxCase(rawData, Seq(
        (memLen === BYTE) -> Cat(Fill(24, ~loadU & rawData(7)), rawData(7,0)),
        (memLen === HALF) -> Cat(Fill(16, ~loadU & rawData(15)), rawData(15,0))
      ))
      entries(i).ready := true.B
    }
  }

  // Find ready entries to writeback
  val readyEntries = valid.zip(entries).map { case (v, e) => v && e.ready }
  val hasReady = readyEntries.reduce(_ || _)
  val readyIndex = PriorityEncoder(readyEntries)
  
  // Generate writeback when entry is ready
  io.OUT_writebackUop.valid := hasReady
  when(hasReady) {
    val selectedEntry = entries(readyIndex)
    
    io.OUT_writebackUop.bits.prd := selectedEntry.prd
    io.OUT_writebackUop.bits.data := selectedEntry.data
    io.OUT_writebackUop.bits.robPtr := selectedEntry.robPtr
    io.OUT_writebackUop.bits.dest := Dest.ROB
    io.OUT_writebackUop.bits.flag := 0.U
    io.OUT_writebackUop.bits.target := 0.U
    
    // Clear entry after writeback
    valid(readyIndex) := false.B
  }.otherwise {
    io.OUT_writebackUop.bits := 0.U.asTypeOf(new WritebackUop)
  }

  // Utility function to get number of valid entries 
  def count(): UInt = PopCount(valid)
}