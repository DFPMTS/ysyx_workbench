import chisel3._
import chisel3.util._
import utils._

/* 
* Main TLB
* (1) Handles MicroTLB misses
* (2) Handles TLBRD/TLBWR/TLBFILL/TLBSRCH
* (3) Handles TLB invalidation
*/


object MicroTLBId {
  val Width = 1

  val ITLB = 0.U(Width.W)
  val DTLB = 1.U(Width.W)
  def apply() = {
    UInt(Width.W)
  }
}

class MainTLBReq extends CoreBundle {
  val vaddr = UInt(XLEN.W)
  val id = MicroTLBId()
}

class MainTLBResp extends CoreBundle {
  val vaddr = UInt(XLEN.W)
  val asid  = UInt(10.W)
  val tlbEntry = new TLBEntry
  val hit = Bool()
  val id  = MicroTLBId()
}

class MainTLBIO extends CoreBundle {
  val IN_mainTLBReq = Vec(2, Flipped(Decoupled(new MainTLBReq)))
  val OUT_mainTLBResp = Valid(new MainTLBResp)

  val IN_VMCSR = Flipped(new VMCSR)

  val IN_TLBCSR = Flipped(new TLBCSR)
  val IN_TLBCtrl = Flipped(new TLBCtrl)
  val IN_InvTLBOp = Flipped(Valid(new InvTLBOp))

  val OUT_TLBOpResult = new TLBOpResult

  val OUT_flushMicroTLB = Bool()

  val IN_flush = Flipped(Bool())
}

class MainTLB(walkCntPerCycle: Int = 4) extends CoreModule {
  assert(32 % walkCntPerCycle == 0, "walkCntPerCycle must divide 32 evenly")
  val walkCntWidth = log2Ceil(32 / walkCntPerCycle)
  val walkCycles = 32 / walkCntPerCycle

  val io = IO(new MainTLBIO)

  // * TLB entry
  val tlb = Reg(Vec(NUM_TLB, new TLBEntry))

  // * Walk state machine
  val sIdle :: sWalk :: sEnd :: Nil = Enum(3)
  val walkReq = Reg(new MainTLBReq)
  val walkCnt = Reg(UInt(walkCntWidth.W))
  val walkASID = Reg(UInt(10.W))

  val state = RegInit(sIdle)


  // * Arbiter of ITLB and DTLB's Main TLB Req
  val inMainTLBReq = Wire(new MainTLBReq)
  val inMainTLBReqValidVec = io.IN_mainTLBReq.map(_.valid)
  val inMainTLBReqValid = inMainTLBReqValidVec.reduce(_ || _)
  val inMainTLBReqValidIndex = PriorityEncoder(inMainTLBReqValidVec)

  for (i <- 0 until 2) {
    io.IN_mainTLBReq(i).ready := false.B
  }
  inMainTLBReq := io.IN_mainTLBReq(inMainTLBReqValidIndex).bits
  io.IN_mainTLBReq(inMainTLBReqValidIndex).ready := state === sIdle
  
  // * Walk slices
  val tlbSlices = Wire(Vec(walkCycles, Vec(walkCntPerCycle, new TLBEntry)))
  val tlbSliceHitVec = Wire(Vec(walkCycles, Vec(walkCntPerCycle, Bool())))
  val tlbSliceHit = Wire(Vec(walkCycles, Bool()))
  val tlbSliceHitEntry = Wire(Vec(walkCycles, new TLBEntry))

  // * Walk result
  val tlbSliceHitReg = Reg(Bool())
  val tlbSliceHitEntryReg = Reg(new TLBEntry)


  switch(state) {
    is(sIdle) {
      when(inMainTLBReqValid) {
        state := sWalk
        walkCnt := 0.U
        walkReq := inMainTLBReq
        walkASID := io.IN_VMCSR.asid
      }
    }
    is(sWalk) {
      when(walkCnt === (walkCycles - 1).U) {
        state := sEnd
      }.otherwise {
        walkCnt := walkCnt + 1.U
        when(tlbSliceHit(walkCnt)) {
          state := sEnd
        }
      }
    }
    is(sEnd) {
      state := sIdle
    }
  }

  // * Walk
  for (i <- 0 until walkCycles) {
    tlbSlices(i) := tlb.slice(i * walkCntPerCycle, (i + 1) * walkCntPerCycle)
    tlbSliceHitVec(i) := tlbSlices(i).map { entry =>
      entry.matchVAddr(walkReq.vaddr, walkASID)
    }
    tlbSliceHit(i) := tlbSliceHitVec(i).reduce(_ || _)
    tlbSliceHitEntry(i) := Mux1H(tlbSliceHitVec(i), tlbSlices(i))
  }

  tlbSliceHitReg := tlbSliceHit(walkCnt)
  tlbSliceHitEntryReg := tlbSliceHitEntry(walkCnt)

  io.OUT_mainTLBResp.valid := state === sEnd
  io.OUT_mainTLBResp.bits.id := walkReq.id
  io.OUT_mainTLBResp.bits.tlbEntry := tlbSliceHitEntryReg
  io.OUT_mainTLBResp.bits.hit := tlbSliceHitReg
  io.OUT_mainTLBResp.bits.vaddr := walkReq.vaddr
  io.OUT_mainTLBResp.bits.asid := walkASID

  // * tlbsrch
  val tlbSrchHitVec = VecInit(tlb.map(e => e.matchVAddr(Cat(io.IN_TLBCSR.tlbehi.VPPN, 0.U(13.W)), io.IN_VMCSR.asid)))
  val tlbSrchIndex = OHToUInt(tlbSrchHitVec)
  val tlbSrchHit = tlbSrchHitVec.reduce(_ || _)

  // * Result of TLBOp
  // ** TLBSRCH
  io.OUT_TLBOpResult.index := tlbSrchIndex
  io.OUT_TLBOpResult.hit := tlbSrchHit
  // ** TLBRD
  io.OUT_TLBOpResult.readEntry := tlb(io.IN_TLBCSR.tlbidx.TLBIDX)

  // ** TLBWR / TLBFILL
  val wrEntry = Wire(new TLBEntry)
  val inTLBRefill = io.IN_TLBCSR.estat.Ecode === 0x3F.U
  wrEntry.VPPN := io.IN_TLBCSR.tlbehi.VPPN
  wrEntry.PS   := io.IN_TLBCSR.tlbidx.PS
  wrEntry.G    := io.IN_TLBCSR.tlbelo(0).G & io.IN_TLBCSR.tlbelo(1).G
  wrEntry.ASID := io.IN_VMCSR.asid
  wrEntry.E    := Mux(inTLBRefill, true.B, ~io.IN_TLBCSR.tlbidx.NE)
  for (i <- 0 until 2) {
    wrEntry.PPN(i).PPN := io.IN_TLBCSR.tlbelo(i).PPN
    wrEntry.PPN(i).PLV := io.IN_TLBCSR.tlbelo(i).PLV
    wrEntry.PPN(i).MAT := io.IN_TLBCSR.tlbelo(i).MAT
    wrEntry.PPN(i).D   := io.IN_TLBCSR.tlbelo(i).D
    wrEntry.PPN(i).V   := io.IN_TLBCSR.tlbelo(i).V
  }
  
  val fillCounter = RegInit(0.U(5.W))
  when(io.IN_TLBCtrl.fill) {
    fillCounter := fillCounter + 1.U
  }

  when(io.IN_TLBCtrl.wr) {
    tlb(io.IN_TLBCSR.tlbidx.TLBIDX) := wrEntry
  }.elsewhen(io.IN_TLBCtrl.fill) {
    tlb(fillCounter) := wrEntry
  }

  val invMask = WireInit(0.U(NUM_TLB.W))
  when(io.IN_InvTLBOp.valid) {
    val invtlbOp = io.IN_InvTLBOp.bits
    val op = invtlbOp.op
    
    val allMask = WireInit(Fill(NUM_TLB, 1.U(1.W)))
    val G1Mask = VecInit(tlb.map(e => e.G)).asUInt
    val G0Mask = VecInit(tlb.map(e => !e.G)).asUInt
    val ASIDMask = VecInit(tlb.map(e => e.ASID === invtlbOp.asid)).asUInt
    val VPPNMask = VecInit(tlb.map(e => e.matchVPPN(invtlbOp.vppn))).asUInt

    when(op === 0x2.U) { // * G=1
      invMask := G1Mask
    }.elsewhen(op === 0x3.U) { // * G=0
      invMask := G0Mask
    }.elsewhen(op === 0x4.U) { // * G=0 && ASID
      invMask := G0Mask & ASIDMask
    }.elsewhen(op === 0x5.U) { // * G=0 && ASID && VA
      invMask := G0Mask & ASIDMask & VPPNMask
    }.elsewhen(op === 0x6.U) {
      invMask := (G1Mask | ASIDMask) & VPPNMask // * (G=1 || ASID) && VA
    }.otherwise {
      invMask := allMask // * All
    }
  }

  for (i <- 0 until NUM_TLB) {
    when(invMask(i)) {
      tlb(i).E := false.B
    }
  }

  // * For every operation that modifies the MainTLB, also flush the MicroTLB
  io.OUT_flushMicroTLB := io.IN_TLBCtrl.fill || io.IN_TLBCtrl.wr || io.IN_InvTLBOp.valid

  when(io.IN_flush) {
    // * A pipeline flush will reset the MainTLB walk state machine
    // * Or an extra mainTLBResp will mess up the IFU/AGU
    state := sIdle
  }
}