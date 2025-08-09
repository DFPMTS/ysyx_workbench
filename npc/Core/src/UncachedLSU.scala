import chisel3._
import chisel3.util._
import utils._
import os.stat

class UncachedLSUIO extends CoreBundle {
  val IN_loadUop = Flipped(Decoupled(new AGUUop))
  val IN_storeUop = Flipped(Decoupled(new AGUUop))
  val IN_memLoadFoward = Flipped(Valid(new MemLoadFoward))
  val IN_uncacheStoreResp = Flipped(Bool())
  val OUT_cacheCtrlUop = Decoupled(new CacheCtrlUop)

  val OUT_loadResult = Decoupled(new LoadResult)

  // * [robTailPtr change] -> [flush]
  // *           |----------> [uop]
  // * do not flush Store
  val IN_flush = Flipped(Bool())
}

class UncachedLSU extends CoreModule {
  val io = IO(new UncachedLSUIO)

  val sIdle :: sLoadReq :: sStoreReq :: sWaitLoadResp :: sWaitStoreResp :: sLoadFin :: Nil = Enum(6)
  val state = RegInit(sIdle)
  val cacheCtrlUop = Reg(new CacheCtrlUop)
  val cacheCtrlUopValid = RegInit(false.B)
  cacheCtrlUop.cacheId := CacheId.DCACHE

  val uop = Reg(new AGUUop)
  val internalWen = RegInit(false.B)
  val internalRen = RegInit(false.B)
  val loadData = Reg(UInt(XLEN.W))
  val loadResult = Wire(new LoadResult)
  val loadResultValid = RegInit(false.B)
  loadResult.data := loadData
  loadResult.ready := true.B
  loadResult.bypassMask := 0.U(4.W)
  loadResult.addr := uop.addr
  loadResult.opcode := uop.opcode
  loadResult.prd := uop.prd
  loadResult.robPtr := uop.robPtr
  loadResult.dest := uop.dest

  io.IN_loadUop.ready := state === sIdle
  io.IN_storeUop.ready := (state === sIdle && !io.IN_loadUop.valid)

  // ! Attention: sb/sh of internal MMIO not implemented

  switch (state) {
    is (sIdle) {
      when (io.IN_loadUop.valid && !io.IN_flush) {
        state := sLoadReq
        uop := io.IN_loadUop.bits

        when(io.IN_loadUop.bits.isInternalMMIO) {
          internalRen := true.B
        }.otherwise {
          cacheCtrlUopValid := true.B
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
        }

      }.elsewhen(io.IN_storeUop.valid) {
        state := sStoreReq
        uop := io.IN_storeUop.bits

        when(io.IN_storeUop.bits.isInternalMMIO) {
          internalWen := true.B
        }.otherwise {
          cacheCtrlUopValid := true.B
          cacheCtrlUop.index := io.IN_storeUop.bits.addr(log2Up(CACHE_LINE_B) + log2Up(DCACHE_SETS) - 1, log2Up(CACHE_LINE_B))
          cacheCtrlUop.wtag := io.IN_storeUop.bits.addr(XLEN - 1, XLEN - 1 - DCACHE_TAG + 1)
          cacheCtrlUop.offset := io.IN_storeUop.bits.addr(log2Up(CACHE_LINE_B) - 1, 0)
          cacheCtrlUop.opcode := MuxLookup(io.IN_storeUop.bits.opcode, CacheOpcode.UNCACHED_SB)(Seq(
            LSUOp.SB -> CacheOpcode.UNCACHED_SB,
            LSUOp.SH -> CacheOpcode.UNCACHED_SH,
            LSUOp.SW -> CacheOpcode.UNCACHED_SW
          ))
          cacheCtrlUop.wdata := io.IN_storeUop.bits.wdata
          cacheCtrlUop.wmask := io.IN_storeUop.bits.mask
        }
      }
    }
    is (sLoadReq) {
      when(io.OUT_cacheCtrlUop.fire) {
        state := sWaitLoadResp
        cacheCtrlUopValid := false.B
      }      
    }
    is (sStoreReq) {
      when(uop.isInternalMMIO) {
        internalWen := false.B
        state := sIdle
      }.otherwise {
        when(io.OUT_cacheCtrlUop.fire) {
          state := sWaitStoreResp
          cacheCtrlUopValid := false.B
        }
      }
    }
    is (sWaitLoadResp) {
      when(io.IN_memLoadFoward.valid && io.IN_memLoadFoward.bits.uncached) {        
        state := sLoadFin
        loadResultValid := true.B
        val offset = if (AXI_DATA_WIDTH == XLEN) 0.U else uop.addr(log2Up(AXI_DATA_WIDTH / 8) - 1, 2)
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
        loadResultValid := false.B
      }
    }
  }

  io.OUT_cacheCtrlUop.valid := cacheCtrlUopValid
  io.OUT_cacheCtrlUop.bits := cacheCtrlUop

  io.OUT_loadResult.valid := loadResultValid
  io.OUT_loadResult.bits := loadResult
}