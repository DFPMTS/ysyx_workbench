import chisel3._
import chisel3.util._
import utils._

class UncachedLSUIO extends CoreBundle {
  val IN_loadUop = Flipped(Decoupled(new AGUUop))
  val IN_storeUop = Flipped(Decoupled(new AGUUop))
  val IN_memLoadFoward = Flipped(Valid(new MemLoadFoward))
  val IN_uncacheStoreResp = Flipped(Bool())
  val OUT_cacheCtrlUop = Decoupled(new CacheCtrlUop)

  val OUT_loadResult = Decoupled(new LoadResult)

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
  cacheCtrlUop.cacheId := CacheId.DCACHE

  val loadUop = Reg(new AGUUop)
  val loadData = Reg(UInt(XLEN.W))
  val loadResult = Wire(new LoadResult)
  loadResult.data := loadData
  loadResult.ready := true.B
  loadResult.bypassMask := 0.U(4.W)
  loadResult.addr := loadUop.addr
  loadResult.opcode := loadUop.opcode
  loadResult.prd := loadUop.prd
  loadResult.robPtr := loadUop.robPtr
  loadResult.dest := loadUop.dest

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
      when(io.OUT_loadResult.fire) {
        state := sIdle
      }
    }
  }

  io.OUT_cacheCtrlUop.valid := state === sLoadReq || state === sStoreReq
  io.OUT_cacheCtrlUop.bits := cacheCtrlUop

  io.OUT_loadResult.valid := state === sLoadFin
  io.OUT_loadResult.bits := loadResult
}