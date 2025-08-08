import chisel3._
import chisel3.util._
import utils._

class L2CacheIO extends CoreBundle {
  val IN_axi = Flipped(new AXI4(AXI_DATA_WIDTH, AXI_ADDR_WIDTH))
  val OUT_axi = new AXI4(AXI_DATA_WIDTH, AXI_ADDR_WIDTH)
  val OUT_L2FastRead = Valid(new L2FastRead)
  val IN_L2FastWrite = Flipped(Decoupled(new L2FastWrite))
}

class L2Cache extends CoreModule {
  val io = IO(new L2CacheIO)

  // Cache parameters
  val CACHE_WAYS = 4
  val CACHE_SETS = 512
  val CACHE_LINE_BYTES = 32
  val IN_NUM_BEATS = CACHE_LINE_BYTES / (AXI_DATA_WIDTH / 8)
  val INDEX_BITS = log2Ceil(CACHE_SETS)
  val OFFSET_BITS = log2Ceil(CACHE_LINE_BYTES)
  val TAG_BITS = AXI_ADDR_WIDTH - INDEX_BITS - OFFSET_BITS

  val CacheLineVec = Vec(IN_NUM_BEATS, UInt(AXI_DATA_WIDTH.W))

  // State machine
  val (sIdle :: sAR :: sAW :: sW :: sLookUp0 :: 
       sLookUp1 :: sLookUp2 :: sLookUpFin :: sReturnDataToL1 :: sForwardAR :: 
       sForwardWaitR :: sFlushActive :: sForwardAW :: sForwardWaitB :: sCollectW :: 
       sWaitReplaceFin :: Nil) = Enum(16)
  val state = RegInit(sFlushActive)

  // Replace Machine
  val (sReplaceIdle :: sReplacePrepareWriteBack :: sReplaceAW :: sReplaceWaitB :: sReplaceWriteNew :: sReplaceFin :: Nil) = Enum(6)
  val replaceState = RegInit(sReplaceIdle)

  // Request registers
  val inArAddr = Reg(UInt(AXI_ADDR_WIDTH.W))
  val inArId = Reg(UInt(4.W))
  val inArLen = Reg(UInt(8.W))
  val inArSize = Reg(UInt(3.W))
  val inArBurst = Reg(UInt(2.W))

  val inAwAddr = Reg(UInt(AXI_ADDR_WIDTH.W))
  val inAwId = Reg(UInt(4.W))
  val inAwLen = Reg(UInt(8.W))
  val inAwSize = Reg(UInt(3.W))

  val READ = 0.U(2.W)
  val WRITE = 1.U(2.W)
  val inOp = RegInit(0.U(2.W))

  val inWCnt = RegInit(0.U(8.W))
  val inWCacheLine = Reg(CacheLineVec)

  val inBValid = RegInit(false.B)
  inBValid := false.B

  // Cache arrays - 2-way, 512 sets
  val tagArray = Seq.fill(CACHE_WAYS)(SyncReadMem(CACHE_SETS, UInt((TAG_BITS + 1).W)))
  val dataArray = Seq.fill(CACHE_WAYS)(SyncReadMem(CACHE_SETS, UInt((CACHE_LINE_BYTES * 8).W)))

  // Address parsing
  def getTag(addr: UInt): UInt = addr(AXI_ADDR_WIDTH-1, INDEX_BITS + OFFSET_BITS)
  def getIndex(addr: UInt): UInt = addr(INDEX_BITS + OFFSET_BITS - 1, OFFSET_BITS)
  def getOffset(addr: UInt): UInt = addr(OFFSET_BITS - 1, 0)

  // Current lookup values
  val lookupAddr = Reg(UInt(AXI_ADDR_WIDTH.W))
  val lookupTag = Wire(UInt(TAG_BITS.W))
  val lookupIndex = Wire(UInt(INDEX_BITS.W))
  val lookupOffset = Wire(UInt(OFFSET_BITS.W))

  lookupTag := getTag(lookupAddr)
  lookupIndex := getIndex(lookupAddr)
  lookupOffset := getOffset(lookupAddr)

  val replaceCounter = RegInit(0.U(2.W))
  when(replaceState === sReplaceFin) {
    replaceCounter := replaceCounter + 1.U
  }

  // Tag comparison and hit detection
  val tagRead1 = Reg(Vec(CACHE_WAYS, UInt((TAG_BITS + 1).W)))
  val dataRead1 = Reg(Vec(CACHE_WAYS, UInt((CACHE_LINE_BYTES * 8).W)))
  val invalidWayVec1 = Wire(Vec(CACHE_WAYS, Bool()))
  val hitWayOH1 = Wire(Vec(CACHE_WAYS, Bool()))
  val hitWay1 = Wire(UInt(log2Up(CACHE_WAYS).W))
  val hit1 = Wire(Bool())
  val hasInvalid1 = Wire(Bool())
  val invalidWay1 = Wire(UInt(CACHE_WAYS.W))

  val hitData2 = Reg(CacheLineVec)
  val hitTag2 = Reg(UInt(TAG_BITS.W))
  val hit2 = Reg(Bool())
  val hitWay2 = Reg(UInt(log2Up(CACHE_WAYS).W))


  val replaceWay1 = Wire(UInt(log2Up(CACHE_WAYS).W))
  val replaceWay2 = Reg(UInt(log2Up(CACHE_WAYS).W))
  val replaceTag2 = Reg(UInt((TAG_BITS + 1).W))
  val replaceData2 = Reg(CacheLineVec)

  val writeTag = Reg(UInt((TAG_BITS + 1).W))
  val writeData = Reg(UInt((CACHE_LINE_BYTES * 8).W))

  val doWriteTag = RegInit(false.B)
  val doWriteData = RegInit(false.B)
  val doFlush = RegInit(false.B)
  doWriteTag := false.B
  doWriteData := false.B
  doFlush := RegNext(state === sFlushActive)

  for (way <- 0 until CACHE_WAYS) {
    tagRead1(way) := tagArray(way).readWrite(lookupIndex, writeTag, true.B, doWriteTag && (replaceWay2 === way.U || doFlush))
    dataRead1(way) := dataArray(way).readWrite(lookupIndex, writeData, true.B, doWriteData && (replaceWay2 === way.U || doFlush))
    invalidWayVec1(way) := !tagRead1(way)(TAG_BITS)
    hitWayOH1(way) := tagRead1(way)(TAG_BITS) && (tagRead1(way)(TAG_BITS-1, 0) === lookupTag)
  }
  hit1 := hitWayOH1.asUInt.orR
  hitWay1 := OHToUInt(hitWayOH1)
  hasInvalid1 := invalidWayVec1.asUInt.orR
  invalidWay1 := PriorityEncoder(invalidWayVec1.asUInt)
  when(state === sReturnDataToL1) {
    replaceWay1 := hitWay1
  }.otherwise{
    when(hit1) {
      replaceWay1 := hitWay1
    }.otherwise{
      when(hasInvalid1) {
        replaceWay1 := invalidWay1
      }.otherwise {
        replaceWay1 := replaceCounter(0)
      }
    }
  }
  replaceTag2 := tagRead1(replaceWay1)
  replaceData2 := dataRead1(replaceWay1).asTypeOf(replaceData2)
  replaceWay2 := replaceWay1

  hitTag2 := Mux1H(hitWayOH1.asUInt, tagRead1).asTypeOf(hitTag2)
  hitData2 := Mux1H(hitWayOH1.asUInt, dataRead1).asTypeOf(hitData2)
  
  hit2 := hitWayOH1.asUInt.orR
  hitWay2 := hitWay1

  // * OUT AR
  val outArValid = RegInit(false.B)
  val outArAddr = Reg(UInt(AXI_ADDR_WIDTH.W))
  val outArId = Reg(UInt(4.W))
  val outArLen = Reg(UInt(8.W))
  val outArSize = Reg(UInt(3.W))

  val isOutARCacheLine = RegInit(false.B)

  // * OUT R
  val outRCnt = RegInit(0.U(8.W))

  // * OUT AW
  val outAwValid = RegInit(false.B)
  val outAwAddr = Reg(UInt(AXI_ADDR_WIDTH.W))
  val outAwId = Reg(UInt(4.W))
  val outAwLen = Reg(UInt(8.W))
  val outAwSize = Reg(UInt(3.W))

  // * OUT W
  val outWValid = RegInit(false.B)
  val outWData = Reg(UInt(AXI_DATA_WIDTH.W))
  val outWStrb = Reg(UInt((AXI_DATA_WIDTH / 8).W))
  val outWLast = RegInit(false.B)
  val outWid = Reg(UInt(4.W))

  val outWCnt = RegInit(0.U(8.W))

  // * IN L2FastRead
  val l2FastRead = Reg(new L2FastRead)
  val l2FastReadValid = RegInit(false.B)
  l2FastReadValid := false.B
  // * IN R
  val rData = Reg(UInt(AXI_DATA_WIDTH.W))
  val rValid = RegInit(false.B)
  val rLast = RegInit(false.B)
  val rResp = RegInit(0.U(2.W))
  val rCnt = RegInit(0.U(8.W))

  rResp := 0.U(2.W)

  val resetIndex = RegInit(0.U(log2Ceil(CACHE_SETS).W))

  switch(state) {
    is(sFlushActive) {
      resetIndex := resetIndex + 1.U
      doWriteTag := true.B
      writeTag := 0.U
      lookupAddr := Cat(resetIndex, 0.U(OFFSET_BITS.W))
      when(resetIndex === (CACHE_SETS - 1).U) {
        state := sIdle
      }
    }
    is(sIdle) {
      when(io.IN_axi.ar.fire) {
        // Read request
        lookupAddr := io.IN_axi.ar.bits.addr
        inArAddr := io.IN_axi.ar.bits.addr
        inArId := io.IN_axi.ar.bits.id
        inArLen := io.IN_axi.ar.bits.len
        inArSize := io.IN_axi.ar.bits.size
        inArBurst := io.IN_axi.ar.bits.burst
        inOp := READ
        state := sAR
      }.elsewhen(io.IN_axi.aw.valid) {
        // Write request
        lookupAddr := io.IN_axi.aw.bits.addr
        inAwAddr := io.IN_axi.aw.bits.addr
        inAwId := io.IN_axi.aw.bits.id
        inAwLen := io.IN_axi.aw.bits.len
        inAwSize := io.IN_axi.aw.bits.size
        inAwId := io.IN_axi.aw.bits.id
        inOp := WRITE
        state := sAW
      }
    }
    is(sAR) {
      // Lookup for read
      when(inArLen =/= 0.U) {
        lookupAddr := inArAddr
        state := sLookUp0
      }.otherwise {
        state := sForwardAR
        isOutARCacheLine := false.B
      }
    }
    is(sAW) {
      // * Collect W data
      lookupAddr := inAwAddr
      inWCnt := 0.U
      when(inAwLen =/= 0.U) {
        state := sCollectW
      }.otherwise {
        state := sForwardAW
      }
    }
    is(sCollectW) {
      when(io.IN_L2FastWrite.fire) {
        inWCacheLine := io.IN_L2FastWrite.bits.data.asTypeOf(inWCacheLine)
        state := sLookUp1
        inBValid := true.B
      }
      when(io.IN_axi.w.fire) {
        inWCacheLine(inWCnt) := io.IN_axi.w.bits.data
        inWCnt := inWCnt + 1.U
        when(io.IN_axi.w.bits.last) {
          state := sLookUp1
        }
      }
    }
    is(sLookUp0) {
      // Send LookUpAddr to Cache Array
      state := sLookUp1
    }
    is(sLookUp1) {
      // Calculate tagRead / dataRead
      state := sLookUpFin
    }
    is(sLookUp2) {
      // Calculate hitData2/hit2/hitWayOH2
      state := sLookUpFin
    }
    is(sLookUpFin) {
      when(inOp === READ) {
        when(hit2) {
          state := sReturnDataToL1
          // rCnt := 1.U
          // rValid := true.B
          // rLast := false.B
          // rData := hitData2(0)
          l2FastReadValid := true.B
          l2FastRead.data := hitData2
          l2FastRead.id := inArId
          l2FastRead.addr := inArAddr
        }.otherwise {
          isOutARCacheLine := true.B
          state := sForwardAR
        }
      }.otherwise {
        state := sWaitReplaceFin
      }
    }
    is(sReturnDataToL1) {
      // Forward data to L1
      // when(io.IN_axi.r.fire) {
      //   rCnt := rCnt + 1.U
      //   rData := hitData2(rCnt)
      //   rLast := (rCnt === (IN_NUM_BEATS - 1).U)
      //   when(rCnt === IN_NUM_BEATS.U) {
      //     rValid := false.B
      //     state := sIdle
      //   }
      // }
      state := sIdle
    }
    is(sForwardAR) {
      // Forward read request to L2
      outArValid := true.B
      outArAddr := inArAddr
      outArId := inArId
      outArLen := inArLen
      outArSize := inArSize

      outRCnt := 0.U

      state := sForwardWaitR
    }
    is(sForwardWaitR) {
      // Wait for response from DDR
      when(io.OUT_axi.ar.fire) {
        outArValid := false.B
      }
      when(isOutARCacheLine) {
        when(io.OUT_axi.r.fire) {
          l2FastRead.data(outRCnt) := io.OUT_axi.r.bits.data
          outRCnt := outRCnt + 1.U
          when(io.OUT_axi.r.bits.last) {
            l2FastReadValid := true.B
            l2FastRead.id := outArId
            l2FastRead.addr := outArAddr

            state := sIdle
          }
        }
      }.otherwise {
        when(io.IN_axi.r.fire) {
          rValid := false.B
          when(io.IN_axi.r.bits.last) {
            state := sIdle
          }
        }
        when(io.OUT_axi.r.fire) {
          rValid := true.B
        }
      }
      rData := io.OUT_axi.r.bits.data
      rResp := io.OUT_axi.r.bits.resp
      rLast := io.OUT_axi.r.bits.last
    }
    is(sForwardAW) {
      // Forward write request to L2
      
      outAwAddr := inAwAddr
      outAwId := inAwId
      outAwLen := inAwLen
      outAwSize := inAwSize

      when(io.IN_axi.w.fire) {
        outAwValid := true.B
        outWValid := true.B
        outWData := io.IN_axi.w.bits.data
        outWStrb := io.IN_axi.w.bits.strb
        outWLast := io.IN_axi.w.bits.last
        outWid := io.IN_axi.w.bits.id
      }
      state := sForwardWaitB
    }
    is(sForwardWaitB) {
      when(io.OUT_axi.aw.fire) {
        outAwValid := false.B
      }      

      when(io.OUT_axi.w.fire) {
        outWValid := false.B        
      }
      
      when(io.OUT_axi.b.fire) {
        state := sIdle
        inBValid := true.B
      }
    }
    is(sWaitReplaceFin) {
      when(replaceState === sReplaceFin) {
        state := sIdle  
      }
    }
  }

  switch(replaceState) {
    is(sReplaceIdle) {
      when(state === sWaitReplaceFin) {
        // Start replacement
        replaceState := sReplacePrepareWriteBack
      }
    }
    is(sReplacePrepareWriteBack) {
      when(replaceTag2(TAG_BITS) && !hit2) {
        // If the tag is valid, and not the same address, prepare to write back
        replaceState := sReplaceAW
      }.otherwise {
        // If the tag is invalid or is the same cacheline, directly write new data
        replaceState := sReplaceWriteNew
      }
    }
    is(sReplaceAW) {
      // Send AW request
      outAwValid := true.B
      outAwAddr := Cat(replaceTag2(TAG_BITS - 1, 0), lookupIndex, 0.U(OFFSET_BITS.W))
      outAwId := inAwId
      outAwLen := (IN_NUM_BEATS - 1).U
      outAwSize := log2Ceil(AXI_DATA_WIDTH / 8).U

      outWCnt := 1.U
      outWValid := true.B
      outWData := replaceData2(0)
      outWStrb := Fill(AXI_DATA_WIDTH / 8, 1.U(1.W))
      outWLast := false.B
      outWid := inAwId

      replaceState := sReplaceWaitB
    }
    is(sReplaceWaitB) {
      when(io.OUT_axi.aw.fire) {
        outAwValid := false.B
      }

      when(io.OUT_axi.w.fire) {
        outWCnt := outWCnt + 1.U
        outWData := replaceData2(outWCnt)
        outWLast := (outWCnt === (IN_NUM_BEATS - 1).U)
        when(outWCnt === IN_NUM_BEATS.U) {
          outWValid := false.B
        }
      }
      when(io.OUT_axi.b.fire) {
        replaceState := sReplaceWriteNew
      }
    }
    is(sReplaceWriteNew) {
      doWriteTag := true.B
      doWriteData := true.B
      writeTag := Cat(true.B, lookupTag)
      writeData := inWCacheLine.asUInt

      replaceState := sReplaceFin
    }
    is(sReplaceFin) {
      replaceState := sReplaceIdle
    }
  }

  // * IN 
  // ** AR
  io.IN_axi.ar.ready := state === sIdle
  // ** R
  io.IN_axi.r.valid := rValid
  io.IN_axi.r.bits.data := rData
  io.IN_axi.r.bits.resp := rResp
  io.IN_axi.r.bits.last := rLast
  io.IN_axi.r.bits.id := inArId
  // ** L2FastRead
  io.OUT_L2FastRead.valid := l2FastReadValid
  io.OUT_L2FastRead.bits := l2FastRead
  // ** L2FastWrite
  io.IN_L2FastWrite.ready := state === sCollectW
  // ** AW
  io.IN_axi.aw.ready := state === sAW
  // ** W
  io.IN_axi.w.ready := state === sForwardAW || state === sCollectW
  // ** B
  io.IN_axi.b.valid := inBValid
  io.IN_axi.b.bits.resp := 0.U
  io.IN_axi.b.bits.id := inAwId

  // * OUT
  // ** AR
  io.OUT_axi.ar.valid := outArValid
  io.OUT_axi.ar.bits.addr := outArAddr
  io.OUT_axi.ar.bits.id := outArId
  io.OUT_axi.ar.bits.len := outArLen
  io.OUT_axi.ar.bits.size := outArSize
  io.OUT_axi.ar.bits.burst := 1.U
  // ** R
  io.OUT_axi.r.ready := true.B
  // ** AW
  io.OUT_axi.aw.valid := outAwValid
  io.OUT_axi.aw.bits.addr := outAwAddr
  io.OUT_axi.aw.bits.id := outAwId
  io.OUT_axi.aw.bits.len := outAwLen
  io.OUT_axi.aw.bits.size := outAwSize
  io.OUT_axi.aw.bits.burst := 1.U
  // ** W
  io.OUT_axi.w.valid := outWValid
  io.OUT_axi.w.bits.data := outWData
  io.OUT_axi.w.bits.strb := outWStrb
  io.OUT_axi.w.bits.last := outWLast
  io.OUT_axi.w.bits.id := outWid
  // ** B
  io.OUT_axi.b.ready := true.B
}