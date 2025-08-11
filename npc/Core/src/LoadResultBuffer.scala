import chisel3._
import chisel3.util._
import utils._

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
  val IN_hitLoadResult = Flipped(Valid(new LoadResult))
  val IN_loadResult = Flipped(Decoupled(new LoadResult))
  val OUT_numEmpty = UInt(log2Up(NUM_MSHR + 1).W)
  
  val IN_memLoadFoward = Flipped(Valid(new MemLoadFoward))
  val IN_L2FastRead = Flipped(Valid(new L2FastRead))
  val OUT_zeroCycleForward = Valid(new WritebackUop)
  val OUT_writebackUop = Valid(new WritebackUop)

  val IN_flush = Flipped(Bool())
}

class LoadResultBuffer extends CoreModule with HasLSUOps {
  val io = IO(new LoadResultBufferIO)
  
  // Load result entries
  val valid = RegInit(VecInit(Seq.fill(NUM_MSHR)(false.B)))
  val entries = Reg(Vec(NUM_MSHR, new LoadResult))
  
  // Find an empty slot for new load
  val emptySlots = valid.map(!_) 
  val hasEmptySlot = emptySlots.reduce(_ || _)
  val allocPtr = PriorityEncoder(emptySlots)
  
  io.IN_loadResult.ready := hasEmptySlot
  io.OUT_numEmpty := PopCount(emptySlots)

  val inHitLoadResult = io.IN_hitLoadResult.bits
  // * Write back now, without writing to result queue
  val inLoadResultWriteback = io.IN_hitLoadResult.valid

  // * Accept new load if there's space and the loadResult is not ready
  when(io.IN_loadResult.fire && (!io.IN_flush || io.IN_loadResult.bits.dest === Dest.PTW)) {
    valid(allocPtr) := true.B
    entries(allocPtr) := io.IN_loadResult.bits
  }
  
  // Forward load data to all entries
  for (i <- 0 until NUM_MSHR) {
    // when(valid(i) && io.IN_memLoadFoward.valid &&
    //       io.IN_memLoadFoward.bits.addr(XLEN - 1, log2Up(AXI_DATA_WIDTH / 8)) === entries(i).addr(XLEN - 1, log2Up(AXI_DATA_WIDTH / 8))) {
    //   val data = Wire(Vec(4, UInt(8.W)))
    //   data := entries(i).data.asTypeOf(data)
    //   val offset = if(AXI_DATA_WIDTH == XLEN) 0.U 
    //                else Cat(entries(i).addr(log2Up(AXI_DATA_WIDTH / 8) - 1, 2), 0.U(2.W))
    //   val bytes = Wire(Vec(AXI_DATA_WIDTH / 8, UInt(8.W)))
    //   bytes := io.IN_memLoadFoward.bits.data.asTypeOf(bytes)
    //   for (j <- 0 until 4) {
    //     when(!entries(i).bypassMask(j)) {
    //       data(j) := bytes(offset + j.U)
    //     }
    //   }
    //   entries(i).data := data.asUInt
    //   entries(i).ready := true.B
    // }
    when(valid(i) && io.IN_L2FastRead.valid &&
          io.IN_L2FastRead.bits.addr(XLEN - 1, log2Up(CACHE_LINE_B)) === entries(i).addr(XLEN - 1, log2Up(CACHE_LINE_B))) {
      val data = Wire(Vec(4, UInt(8.W)))
      data := entries(i).data.asTypeOf(data)
      val offset = entries(i).addr(log2Up(CACHE_LINE_B) - 1, 2)
      val word = Wire(Vec(4, UInt(8.W)))
      word := io.IN_L2FastRead.bits.data(offset).asTypeOf(word)
      for (j <- 0 until 4) {
        when(!entries(i).bypassMask(j)) {
          data(j) := word(j)
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
  // val wbUopValid = WireInit(false.B)
  // val wbUop = WireInit(0.U.asTypeOf(new WritebackUop))

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
  val wbLoadResult = Mux(inLoadResultWriteback, inHitLoadResult, entries(readyIndex))

  wbUopValid := inLoadResultWriteback || hasReady
  wbUop := loadResultToWriteback(wbLoadResult)

  if (DO_SPEC_WAKEUP) {
    io.OUT_zeroCycleForward.valid := inLoadResultWriteback
  } else {
    io.OUT_zeroCycleForward.valid := false.B
  }
  io.OUT_zeroCycleForward.bits := loadResultToWriteback(inHitLoadResult)

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
    
    for(i <- 0 until NUM_MSHR) {
      when(valid(i) && entries(i).dest === Dest.ROB) {
        valid(i) := false.B
      }
    }
  }
}