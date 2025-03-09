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
  val data = UInt(32.W)
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
  val OUT_MSHR = Vec(1, new MSHR)

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
  // * read from Mem
  val memReadAddr = UInt(XLEN.W)
  val needReadMem = Bool()
  // * write to Mem
  val memWriteAddr = UInt(XLEN.W)
  val needReadCache = Bool()
  val needWriteMem = Bool()
  // * progress counter: CACHE_LINE * 8/AXI_DATA_WIDTH
  val counter = UInt(log2Up(CACHE_LINE * 8 / AXI_DATA_WIDTH).W)

  val axiReadDone = Bool()
  val axiWriteDone = Bool()

  // * data
  val wdata = UInt((CACHE_LINE * 8).W)
  val wmask = UInt(CACHE_LINE.W)
}

class CacheController extends CoreModule {
  val io = IO(new CacheControllerIO)

  val validIndex = PriorityEncoder(io.IN_cacheCtrlUop.map(_.valid))

  val uop = io.IN_cacheCtrlUop(validIndex).bits
  val uopValid = io.IN_cacheCtrlUop(validIndex).valid

  val mshr = RegInit(VecInit(Seq.fill(1)(0.U.asTypeOf(new MSHR))))

  for (i <- 0 until 2) {
    io.IN_cacheCtrlUop(i).ready := false.B
  }
  io.IN_cacheCtrlUop(validIndex).ready := !mshr(0).valid
  
  io.OUT_MSHR := mshr

  when(!mshr(0).valid && uopValid) {
    mshr(0).valid := true.B
    mshr(0).uncached := false.B
    mshr(0).needReadMem := false.B
    mshr(0).needWriteMem := false.B
    mshr(0).axiReadDone := true.B
    mshr(0).axiWriteDone := true.B
    mshr(0).counter := 0.U
    mshr(0).opcode := uop.opcode
    mshr(0).wdata := uop.wdata
    mshr(0).wmask := uop.wmask
    val raddr = Cat(uop.rtag, uop.index, 0.U(log2Up(CACHE_LINE).W))
    val waddr = Cat(uop.wtag, uop.index, 0.U(log2Up(CACHE_LINE).W))
    when(uop.opcode === CacheOpcode.LOAD) {
      // * just load to cache
      mshr(0).memReadAddr := raddr

      mshr(0).needReadMem := true.B

      mshr(0).axiReadDone := false.B
    }.elsewhen(uop.opcode === CacheOpcode.REPLACE) {
      // * replace cache line
      mshr(0).memReadAddr := raddr
      mshr(0).memWriteAddr := waddr

      mshr(0).needReadMem := true.B
      mshr(0).needReadCache := true.B
      mshr(0).needWriteMem := true.B

      mshr(0).axiReadDone := false.B
      mshr(0).axiWriteDone := false.B
    }.elsewhen(CacheOpcode.isUnCachedLoad(uop.opcode)) {
      // * uncached load
      mshr(0).uncached := true.B

      mshr(0).memReadAddr := raddr

      mshr(0).needReadMem := true.B

      mshr(0).axiReadDone := false.B
    }.elsewhen(CacheOpcode.isUnCachedStore(uop.opcode)) {
      // * uncached store
      mshr(0).uncached := true.B

      mshr(0).memWriteAddr := waddr
      mshr(0).needReadCache := true.B
      mshr(0).needWriteMem := true.B

      mshr(0).axiWriteDone := false.B
    }
  }.elsewhen(mshr(0).valid && mshr(0).axiReadDone && mshr(0).axiWriteDone) {
    mshr(0).valid := false.B
  }

  val wValidReg = RegInit(false.B)
  val wDataReg = RegInit(0.U(AXI_DATA_WIDTH.W))
  val wMaskReg = Reg(UInt(4.W))

  // * forward load data
  io.OUT_memLoadFoward.valid := io.OUT_axi.r.valid
  io.OUT_memLoadFoward.bits.addr := mshr(0).memReadAddr
  io.OUT_memLoadFoward.bits.data := io.OUT_axi.r.bits.data
  io.OUT_memLoadFoward.bits.uncached := mshr(0).uncached

  // * cache interface
  // ** cache read
  val dataReadRespValid = RegNext(io.OUT_DDataRead.valid)
  io.OUT_DDataRead.bits.addr := mshr(0).memWriteAddr
  io.OUT_DDataRead.bits.data := 0.U
  io.OUT_DDataRead.bits.wmask := 0.U
  io.OUT_DDataRead.bits.write := false.B
  io.OUT_DDataRead.valid := mshr(0).valid && mshr(0).needReadCache && !mshr(0).uncached
  when(io.OUT_axi.w.fire) {
    wValidReg := false.B
  }
  when(!wValidReg || io.OUT_axi.w.fire) {    
    when(mshr(0).valid) {      
      when(!mshr(0).uncached && dataReadRespValid) {
        wDataReg := io.IN_DDataResp.data(0)        
        wMaskReg := "b1111".U
        wValidReg := mshr(0).needReadCache
        mshr(0).needReadCache := false.B
      }.elsewhen(mshr(0).uncached) {
        wDataReg := mshr(0).wdata
        wMaskReg := mshr(0).wmask
        wValidReg := mshr(0).needReadCache
        mshr(0).needReadCache := false.B
      }      
    }
  }
  // ** cache write
  io.OUT_DDataWrite := 0.U.asTypeOf(io.OUT_DDataWrite)
  when(io.OUT_axi.r.valid) {
    val wdata = Wire(Vec(CACHE_LINE, UInt(8.W)))
    wdata := io.OUT_axi.r.bits.data.asTypeOf(wdata)
    for (i <- 0 until CACHE_LINE) {
      when(mshr(0).wmask(i)) {
        wdata(i) := mshr(0).wdata((i + 1) * 8 - 1, i * 8)
      }
    }
    io.OUT_DDataWrite.bits.addr := mshr(0).memReadAddr
    io.OUT_DDataWrite.bits.data := wdata.asUInt
    io.OUT_DDataWrite.bits.wmask := Fill(AXI_DATA_WIDTH/8, 1.U(1.W)) << mshr(0).counter
    io.OUT_DDataWrite.bits.write := true.B
    io.OUT_DDataWrite.valid := !CacheOpcode.isUnCached(mshr(0).opcode)
    mshr(0).axiReadDone := true.B
  }

  // * axi interface
  // ** ar
  val arValidReg = RegInit(false.B)
  val arAddrReg = RegInit(0.U(AXI_ADDR_WIDTH.W))
  when(!arValidReg || io.OUT_axi.ar.fire) {
    when(mshr(0).valid && mshr(0).needReadMem && (!mshr(0).needReadCache || mshr(0).counter > 0.U)) {
      arValidReg := mshr(0).needReadMem
      arAddrReg := mshr(0).memReadAddr
      mshr(0).needReadMem := false.B
    }.otherwise {
      arValidReg := false.B
    }
  }
  io.OUT_axi.ar.valid := arValidReg
  io.OUT_axi.ar.bits.addr := arAddrReg
  io.OUT_axi.ar.bits.len := 0.U
  io.OUT_axi.ar.bits.size := 2.U
  io.OUT_axi.ar.bits.burst := 1.U
  io.OUT_axi.ar.bits.id := 0.U
  
  // ** aw
  val awValidReg = RegInit(false.B)
  val awAddrReg = RegInit(0.U(AXI_ADDR_WIDTH.W))
  when(!awValidReg || io.OUT_axi.aw.fire) {
    when(mshr(0).valid && mshr(0).needWriteMem) {
      awValidReg := mshr(0).needWriteMem
      awAddrReg := mshr(0).memWriteAddr
      mshr(0).needWriteMem := false.B
    }.otherwise {
      awValidReg := false.B
    }
  }
  io.OUT_axi.aw.valid := awValidReg
  io.OUT_axi.aw.bits.addr := awAddrReg
  io.OUT_axi.aw.bits.len := 0.U
  io.OUT_axi.aw.bits.size := 2.U
  io.OUT_axi.aw.bits.burst := 1.U
  io.OUT_axi.aw.bits.id := 0.U

  // ** w
  io.OUT_axi.w.valid := wValidReg
  io.OUT_axi.w.bits.data := wDataReg
  io.OUT_axi.w.bits.strb := "b1111".U
  io.OUT_axi.w.bits.last := true.B

  // ** r
  io.OUT_axi.r.ready := true.B

  // ** b
  io.OUT_uncacheStoreResp := io.OUT_axi.b.fire
  io.OUT_axi.b.ready := true.B
  when(io.OUT_axi.b.fire) {
    mshr(0).axiWriteDone := true.B
  }
} 