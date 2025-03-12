import chisel3._
import chisel3.util._
import utils._

class MMUResp extends CoreBundle {
  val isSuper = Bool()
  val pte = PTE()
  def vaddrToPaddr(vaddr: UInt) = {
    Mux(isSuper, Cat(pte.ppn1, vaddr(21, 0)), Cat(pte.ppn1, pte.ppn0, vaddr(11, 0)))
  }
  def loadStorePermFail(write: Bool, vmCSR: VMCSR) = {
    !pte.v || // * Invalid
    !pte.a || // * svadu
    Mux(write, !pte.r || !pte.w || !pte.d, 
               !pte.r && !(vmCSR.mxr && pte.x)) || // * r/w perm fail
    (!pte.u && vmCSR.epm === Priv.U) || // * User program can only access page with U bit set
    (pte.u && !vmCSR.sum && vmCSR.epm === Priv.S) || // * SUM
    (isSuper && pte.ppn0 =/= 0.U) // * Super page must align to 4MB
  }
  def executePermFail(vmCSR: VMCSR) = {
    !pte.v || // * Invalid
    !pte.a || // * svadu
    !pte.x || // * Execute perm fail
    (!pte.r && pte.w) || // * illegal combination
    (!pte.u && vmCSR.priv === Priv.U) || // * User program can only access page with U bit set
    (isSuper && pte.ppn0 =/= 0.U)
  }
}

class TLBReq extends CoreBundle {
  val vpn = UInt(PAGE_NR_LEN.W)
}

class TLBResp extends MMUResp {  

}

class PTWReq extends CoreBundle {
  val vpn = UInt(PAGE_NR_LEN.W)
}

class PTWResp extends MMUResp {
  val vpn = UInt(PAGE_NR_LEN.W)
  val id  = UInt(1.W)
}

class AGUUop extends CoreBundle {
  val prd  = UInt(PREG_IDX_W)

  val addr = UInt(XLEN.W)
  val wdata = UInt(XLEN.W)

  val dest = UInt(1.W)

  val robPtr = RingBufferPtr(ROB_SIZE)
  val ldqPtr = RingBufferPtr(LDQ_SIZE)
  val stqPtr = RingBufferPtr(STQ_SIZE)

  val fuType = UInt(FuTypeWidth.W)
  val opcode = UInt(OpcodeWidth.W)

  val predTarget = UInt(XLEN.W)
  val compressed = Bool()
}

class AGUIO extends CoreBundle {
  val IN_VMCSR      = Flipped(new VMCSR)

  val IN_readRegUop  = Flipped(Decoupled(new ReadRegUop))

  val OUT_TLBReq     = Valid(new TLBReq)
  val IN_TLBResp    = Flipped(Valid(new TLBResp))

  val OUT_PTWReq     = Decoupled(new PTWReq)
  val IN_PTWResp     = Flipped(Valid(new PTWResp))

  val OUT_AGUUop     = Valid(new AGUUop)
  val OUT_writebackUop = Valid(new WritebackUop)
  val OUT_xtvalRec   = Valid(new XtvalRec)

  val IN_flush       = Flipped(Bool())
}

class AGU extends CoreModule {
  val io = IO(new AGUIO)
  
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
  val inValid = io.IN_readRegUop.valid

  val memLen = Mux(inUop.fuType === FuType.AMO, 2.U, uopNext.opcode(2, 1))
  val addrMisalign = MuxLookup(memLen, false.B)(Seq(
                            "b00".U -> false.B,
                            "b01".U -> uopNext.addr(0,0).orR,
                            "b10".U -> uopNext.addr(1,0).orR,
                            "b11".U -> false.B))
  val doTranslate = io.IN_VMCSR.mode === 1.U && io.IN_VMCSR.epm < Priv.M  
  // * calculate addr
  val addr = uopNext.addr;

  // * TLB access    
  io.OUT_TLBReq.valid := uopNextValid && doTranslate
  io.OUT_TLBReq.bits.vpn := addr(XLEN - 1, 12)

  val tlbHit = !doTranslate || io.IN_TLBResp.valid

  // * Need translate && TLB miss
  tlbMissQueue.io.IN_uop.valid := inValid && !addrMisalign && !tlbHit
  tlbMissQueue.io.IN_uop.bits := uopNext
  tlbMissQueue.io.IN_flush := io.IN_flush

  // * No translate/TLB hit:  
  when(inValid) {
    uopNextValid := true.B
    uopNext.prd := inUop.prd
    uopNext.addr := Mux(inUop.fuType === FuType.AMO, inUop.src1, inUop.src1 + inUop.imm)
    uopNext.wdata := inUop.src2

    uopNext.dest := Dest.ROB

    uopNext.robPtr := inUop.robPtr
    uopNext.ldqPtr := inUop.ldqPtr
    uopNext.stqPtr := inUop.stqPtr

    uopNext.fuType := inUop.fuType
    uopNext.opcode := inUop.opcode
    uopNext.predTarget := inUop.predTarget
    uopNext.compressed := inUop.compressed
  }.otherwise {
    uopNextValid := tlbMissQueue.io.OUT_uop.valid
    uopNext := tlbMissQueue.io.OUT_uop.bits    
  }
  
  val ptwReq = Reg(new PTWReq)
  val ptwReqValid = RegInit(false.B)

  val ptwReqNext = Wire(new PTWReq)
  val ptwReqNextValid = WireInit(false.B)
  ptwReqNext.vpn := uopNext.addr(XLEN - 1, 12)

  val misalignFault = WireInit(0.U(FLAG_W))
  val pageFault = WireInit(0.U(FLAG_W))

  when(inUop.fuType === FuType.AMO) {
    when(inUop.opcode === AMOOp.LR_W) {
      misalignFault := FlagOp.LOAD_ADDR_MISALIGNED
      pageFault := FlagOp.LOAD_PAGE_FAULT
    }.otherwise {
      misalignFault := FlagOp.STORE_ADDR_MISALIGNED
      pageFault := FlagOp.STORE_PAGE_FAULT
    }
  }.elsewhen(inUop.fuType === FuType.LSU) {
    when(inUop.opcode(3)) {
      misalignFault := FlagOp.STORE_ADDR_MISALIGNED
      pageFault := FlagOp.STORE_PAGE_FAULT
    }.otherwise {
      misalignFault := FlagOp.LOAD_ADDR_MISALIGNED
      pageFault := FlagOp.LOAD_PAGE_FAULT
    }
  }

  val storeWbUopValid = io.OUT_AGUUop.valid && LSUOp.isStore(io.OUT_AGUUop.bits.opcode)
  val storeWbUop = WireInit(0.U.asTypeOf(new WritebackUop))
  storeWbUop.data := 0.U
  storeWbUop.dest := Dest.ROB
  storeWbUop.robPtr := io.OUT_AGUUop.bits.robPtr
  storeWbUop.flag := FlagOp.NONE
  storeWbUop.prd  := ZERO

  wbUop.data := 0.U
  wbUopValid := false.B
  wbUop.dest := Dest.ROB
  wbUop.robPtr := uopNext.robPtr
  wbUop.flag := Mux(addrMisalign, misalignFault, pageFault)
  wbUop.prd  := ZERO
  
  xtvalRec.tval := uopNext.addr
  xtvalRec.robPtr := uopNext.robPtr

  uopValid := false.B
  wbUopValid := false.B
  tlbMissQueue.io.OUT_uop.ready := false.B
  when(uopNextValid) {
    when(addrMisalign) {
      wbUopValid := true.B
      uopValid := false.B
    }.elsewhen(tlbHit) {
      val isWrite = (uopNext.fuType === FuType.LSU && uopNext.opcode(3)) || (uopNext.fuType === FuType.AMO && uopNext.opcode =/= AMOOp.LR_W)
      val permFail = io.IN_TLBResp.bits.loadStorePermFail(isWrite, io.IN_VMCSR)
      when(true.B) {
        uop := uopNext
        uop.addr := Mux(doTranslate, io.IN_TLBResp.bits.vaddrToPaddr(uopNext.addr), uopNext.addr)
        uopValid := Mux(doTranslate, !permFail, true.B)
        when(doTranslate && permFail) {
          wbUopValid := true.B
        }
        when(!inValid) {
          tlbMissQueue.io.OUT_uop.ready := true.B
        }
      }
    }.otherwise {
      ptwReqNextValid := true.B
    }
  }

  when(!ptwReqValid) {
    ptwReq := ptwReqNext
    ptwReqValid := ptwReqNextValid
  }
  when(io.IN_PTWResp.valid && io.IN_PTWResp.bits.id === 1.U) {
    ptwReqValid := false.B
  }

  io.OUT_PTWReq.valid := ptwReqValid
  io.OUT_PTWReq.bits := ptwReq

  io.OUT_writebackUop.valid := wbUopValid || storeWbUopValid
  io.OUT_writebackUop.bits := Mux(wbUopValid, wbUop, storeWbUop)

  io.OUT_xtvalRec.valid := wbUopValid
  io.OUT_xtvalRec.bits := xtvalRec

  when(io.IN_flush) {
    uopValid := false.B
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