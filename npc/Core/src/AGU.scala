import chisel3._
import chisel3.util._
import utils._

class MMUException extends CoreBundle {
  val ALE = Bool() // Mem Only
  val TLBR = Bool()
  val PIL = Bool() // Load Only
  val PIS = Bool() // Store Only
  val PIF = Bool() // Fetch Only
  val PPI = Bool()
  val PME = Bool() // Store Only
  val ADEF = Bool() // Fetch Only

  def hasFault: Bool = {
    ALE || TLBR || PIL || PIS || PIF || PPI || PME || ADEF
  }

  def toLoadStoreFlag: UInt = {
    val flag = WireInit(FlagOp.NONE)

    when(ALE) {
      flag := FlagOp.ALE
    }.elsewhen(TLBR) {
      flag := FlagOp.TLBR
    }.elsewhen(PIL) {
      flag := FlagOp.PIL
    }.elsewhen(PIS) {
      flag := FlagOp.PIS
    }.elsewhen(PPI) {
      flag := FlagOp.PPI
    }.elsewhen(PME) {
      flag := FlagOp.PME
    }
    flag 
  }
}

class MMUResp extends CoreBundle {
  val paddr = UInt(XLEN.W)
  val mat = UInt(2.W)
  val exception = new MMUException
}

class TLBReq extends CoreBundle {
  val vaddr = UInt(XLEN.W)
  val memLen = UInt(2.W)
  val isWrite = Bool()
  val isFetch = Bool()
}

class TLBResp extends MMUResp {  

}

class PTWReq extends CoreBundle {
  val vpn = UInt(PAGE_NR_LEN.W)
  val stqPtr = RingBufferPtr(STQ_SIZE)
}

class PTWResp extends MMUResp {
  val vpn = UInt(PAGE_NR_LEN.W)
  val id  = UInt(1.W)
}

class AGUUop extends CoreBundle {
  val prd  = UInt(PREG_IDX_W)

  val addr = UInt(XLEN.W)
  val wdata = UInt(XLEN.W)
  val mask = UInt((XLEN / 8).W)

  val dest = UInt(1.W)

  val robPtr = RingBufferPtr(ROB_SIZE)
  val ldqPtr = RingBufferPtr(LDQ_SIZE)
  val stqPtr = RingBufferPtr(STQ_SIZE)

  val fuType = UInt(FuTypeWidth.W)
  val opcode = UInt(OpcodeWidth.W)

  val isInternalMMIO = Bool()
  val isUncached = Bool()
  val virtualIndexIssued = Bool()

  val predTarget = UInt(XLEN.W)
  val compressed = Bool()
}

class AGUIO extends CoreBundle {
  val IN_VMCSR      = Flipped(new VMCSR)

  val IN_readRegUop  = Flipped(Decoupled(new ReadRegUop))

  val OUT_TLBReq     = Valid(new TLBReq)
  val IN_TLBResp    = Flipped(Valid(new TLBResp))

  val OUT_mainTLBReq = Decoupled(new MainTLBReq)
  val IN_mainTLBResp = Flipped(Valid(new MainTLBResp))

  val OUT_AGUUop     = Valid(new AGUUop)
  val OUT_writebackUop = Valid(new WritebackUop)
  val OUT_xtvalRec   = Valid(new XtvalRec)

  val OUT_virtualIndex = Decoupled(new VirtualIndex)

  val IN_flush       = Flipped(Bool())
}

class AGU extends CoreModule {
  val io = IO(new AGUIO)
  
  def getWmask(aguUop: AGUUop): UInt = {
    val memLen = aguUop.opcode(2, 1)
    val addrOffset = aguUop.addr(log2Up(XLEN/8) - 1, 0)
    val mask = MuxLookup(memLen, 0.U(4.W))(
      Seq(
        0.U(2.W) -> "b0001".U,
        1.U(2.W) -> "b0011".U,
        2.U(2.W) -> "b1111".U
      )
    ) << addrOffset
    mask(3, 0)
  }
  def getShiftedData(aguUop: AGUUop): UInt = {
    val addrOffset = aguUop.addr(log2Up(XLEN/8) - 1, 0)
    (aguUop.wdata << (addrOffset << 3))(XLEN - 1, 0)
  }

  val tlbMissQueue = Module(new TLBMissQueue(4))
  tlbMissQueue.io.IN_flush := io.IN_flush

  val uopNextValid = Wire(Bool())
  val uopNext = Wire(new AGUUop)
  val uop = Reg(new AGUUop)
  val uopValid = RegInit(false.B)

  val wbUop = Reg(new WritebackUop)
  val wbUopValid = RegInit(false.B)
  val xtvalRec = Reg(new XtvalRec)

  io.IN_readRegUop.ready := tlbMissQueue.io.IN_uop.ready

  val inUop = io.IN_readRegUop.bits
  val inValid = io.IN_readRegUop.fire

  val memLen = Mux(inUop.fuType === FuType.AMO, 2.U, uopNext.opcode(2, 1))
  
  val doTranslate = io.IN_VMCSR.doTranslate()
  // * calculate addr
  val addr = uopNext.addr;

  val isStore = (uopNext.fuType === FuType.LSU && LSUOp.isStore(uopNext.opcode)) || 
                (uopNext.fuType === FuType.AMO && uopNext.opcode =/= AMOOp.LR_W)


  // * TLB access    
  io.OUT_TLBReq.valid := uopNextValid && doTranslate
  io.OUT_TLBReq.bits.vaddr := addr
  io.OUT_TLBReq.bits.memLen := memLen
  io.OUT_TLBReq.bits.isWrite := isStore
  io.OUT_TLBReq.bits.isFetch := false.B

  val translateDone = !doTranslate || io.IN_TLBResp.valid

  // * Need translate && TLB miss
  tlbMissQueue.io.IN_uop.valid := inValid && !translateDone
  tlbMissQueue.io.IN_uop.bits := uopNext
  tlbMissQueue.io.IN_flush := io.IN_flush

  // * No translate/TLB hit:  
  when(inValid) {
    uopNextValid := true.B
    uopNext.prd := inUop.prd
    // ! uopNext.addr := Mux(inUop.fuType === FuType.AMO, inUop.src1, inUop.src1 + inUop.imm)
    uopNext.addr := inUop.src1 + inUop.imm
    uopNext.wdata := inUop.src2

    uopNext.dest := Dest.ROB

    uopNext.robPtr := inUop.robPtr
    uopNext.ldqPtr := inUop.ldqPtr
    uopNext.stqPtr := inUop.stqPtr

    uopNext.fuType := inUop.fuType
    uopNext.opcode := inUop.opcode
    uopNext.predTarget := inUop.predTarget
    uopNext.compressed := inUop.compressed

    uopNext.virtualIndexIssued := io.OUT_virtualIndex.ready

    uopNext.mask := DontCare
    uopNext.isInternalMMIO := DontCare
    uopNext.isUncached := DontCare
  }.otherwise {
    uopNextValid := tlbMissQueue.io.OUT_uop.valid
    uopNext := tlbMissQueue.io.OUT_uop.bits    
  }

  val storeWbUopValid = io.OUT_AGUUop.valid && io.OUT_AGUUop.bits.fuType === FuType.LSU && LSUOp.isStore(io.OUT_AGUUop.bits.opcode)
  val storeWbUop = WireInit(0.U.asTypeOf(new WritebackUop))
  storeWbUop.data := 0.U
  storeWbUop.dest := Dest.ROB
  storeWbUop.robPtr := io.OUT_AGUUop.bits.robPtr
  storeWbUop.flag := FlagOp.NONE
  storeWbUop.prd  := ZERO

  val flag = io.IN_TLBResp.bits.exception.toLoadStoreFlag
  val translateFail = io.IN_TLBResp.bits.exception.hasFault

  wbUop.data := 0.U
  wbUopValid := false.B
  wbUop.dest := Dest.ROB
  wbUop.robPtr := uopNext.robPtr
  wbUop.flag := flag
  wbUop.prd  := ZERO
  
  xtvalRec.tval := uopNext.addr
  xtvalRec.robPtr := uopNext.robPtr

  val mainTLBReqValid = RegInit(false.B)
  val mainTLBReq = Reg(new MainTLBReq)  
  val mainTLBRespValid = io.IN_mainTLBResp.valid && io.IN_mainTLBResp.bits.id === MicroTLBId.DTLB
  
  io.OUT_mainTLBReq.valid := mainTLBReqValid
  io.OUT_mainTLBReq.bits := mainTLBReq

  mainTLBReqValid := uopNextValid && !translateDone
  when(mainTLBRespValid) {
    mainTLBReqValid := false.B
  }
  mainTLBReq.vaddr := uopNext.addr
  mainTLBReq.id := MicroTLBId.DTLB

  uopValid := false.B
  wbUopValid := false.B
  tlbMissQueue.io.OUT_uop.ready := false.B
  when(uopNextValid) {
    when(translateDone) {
      when(true.B) {
        uop := uopNext
        val paddr = Mux(doTranslate, io.IN_TLBResp.bits.paddr, uopNext.addr)
        uop.addr := paddr
        uop.isInternalMMIO := false.B
        uop.isUncached := Mux(doTranslate, io.IN_TLBResp.bits.mat, io.IN_VMCSR.datm) === 0.U
        uop.mask := getWmask(uopNext)
        uop.wdata := getShiftedData(uopNext)

        uopValid := Mux(doTranslate, !translateFail, true.B)
        when((doTranslate && translateFail) || io.IN_TLBResp.bits.exception.ALE) {
          wbUopValid := true.B
        }
        when(!inValid) {
          tlbMissQueue.io.OUT_uop.ready := true.B
        }
      }
    }
  }

  io.OUT_writebackUop.valid := wbUopValid || storeWbUopValid
  io.OUT_writebackUop.bits := Mux(wbUopValid, wbUop, storeWbUop)

  io.OUT_xtvalRec.valid := wbUopValid
  io.OUT_xtvalRec.bits := xtvalRec

  io.OUT_virtualIndex.valid := io.IN_readRegUop.fire && LSUOp.isLoad(io.IN_readRegUop.bits.opcode) && io.IN_readRegUop.bits.fuType === FuType.LSU
  io.OUT_virtualIndex.bits.index := (inUop.src1 + inUop.imm)(log2Up(DCACHE_SETS) + log2Up(CACHE_LINE_B) - 1, log2Up(CACHE_LINE_B))
  io.OUT_virtualIndex.bits.opcode := io.IN_readRegUop.bits.opcode

  when(io.IN_flush) {
    uopValid := false.B
    wbUopValid := false.B
  }

  io.OUT_AGUUop.valid := uopValid
  io.OUT_AGUUop.bits := uop
}

class TLBMissQueueIO extends CoreBundle {
  val IN_uop = Flipped(Decoupled(new AGUUop))  
  val OUT_uop = Decoupled(new AGUUop)    
  val IN_flush = Input(Bool())
}

class TLBMissQueue(size: Int) extends CoreModule {
  val io = IO(new TLBMissQueueIO)

  val valid = RegInit(VecInit(Seq.fill(size)(false.B)))
  val entry = Reg(Vec(size, new AGUUop))

  val hasEmpty = valid.contains(false.B)
  val hasValid = valid.contains(true.B)

  io.IN_uop.ready := hasEmpty
    
  when(io.IN_uop.fire) {
    val enqIndex = valid.indexWhere(_ === false.B)
    valid(enqIndex) := true.B
    entry(enqIndex) := io.IN_uop.bits
    entry(enqIndex).virtualIndexIssued := false.B
  }

  // * Dequeue valid && ready
  val deqIndex = valid.indexWhere(_ === true.B)

  val uop = Reg(new AGUUop)
  val uopValid = RegInit(false.B)

  val canDequeue = !uopValid || io.OUT_uop.ready
  when(canDequeue) {
    uop := entry(deqIndex)
    uopValid := hasValid
    valid(deqIndex) := false.B    
  }

  when(io.IN_flush) {
    valid := VecInit(Seq.fill(size)(false.B))
    uopValid := false.B
  }

  io.OUT_uop.bits := uop
  io.OUT_uop.valid := uopValid
}