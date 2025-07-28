import chisel3._
import chisel3.util._
import utils._

class TLBCtrl extends CoreBundle {
  val wr   = Bool()
  val fill = Bool()
}

class TLBOpResult extends CoreBundle {
  // * TLBSRCH
  val hit = Bool()
  val index = UInt(5.W)
  // * TLBRD
  val readEntry = new TLBEntry
}

class InvTLBOp extends CoreBundle {
  val op = UInt(5.W)
  val asid = UInt(10.W)
  val vppn = UInt(19.W)
}

class TLBIO extends CoreBundle {
  val IN_TLBReq = Flipped(Valid(new TLBReq))
  val OUT_TLBResp = Valid(new TLBResp)

  val IN_VMCSR = Flipped(new VMCSR)

  val IN_TLBCSR = Flipped(new TLBCSR)
  val IN_TLBCtrl = Flipped(new TLBCtrl)
  val IN_InvTLBOp = Flipped(Valid(new InvTLBOp))

  val OUT_TLBOpResult = new TLBOpResult
}

class TLBPPN extends CoreBundle {
  val PPN  = UInt(20.W)
  val PLV  = UInt(2.W)
  val MAT  = UInt(2.W)
  val D    = Bool()
  val V    = Bool()

  def toTLBELO(G: UInt): TLBELO = {
    val tlbelo = WireInit(0.U.asTypeOf(new TLBELO))
    tlbelo.PPN := PPN
    tlbelo.G   := G
    tlbelo.PLV := PLV
    tlbelo.MAT := MAT
    tlbelo.D   := D
    tlbelo.V   := V
    tlbelo
  }
}

class TLBEntry extends CoreBundle {
  val VPPN = UInt(19.W)
  val PS   = UInt(6.W)
  val G    = Bool()
  val ASID = UInt(10.W)
  val E    = Bool()

  val PPN = Vec(2, new TLBPPN)

  def matchVAddr(vaddr: UInt, asid: UInt): Bool = {
    val is21 = PS === 21.U
    val vppn = vaddr(31, 13)
    val asidMatch = G || (asid === ASID)

    (E && matchVPPN(vppn) && asidMatch)
  }

  def matchVPPN(vppn: UInt): Bool = {
    val is21 = PS === 21.U
    val vppnMatch = Mux(is21,
      vppn(18, 9) === VPPN(18, 9),
      vppn === VPPN,
    )

    vppnMatch
  }
}

class TLB(id: Int) extends CoreModule {
  val io = IO(new TLBIO)

  val entry = RegInit(VecInit(Seq.fill(NUM_TLB)({
    0.U.asTypeOf(new TLBEntry)
  })))
  // * DMW
  val (dmwHit, dmwPAddr, dmwMAT) = io.IN_VMCSR.matchDMW(io.IN_TLBReq.bits.vaddr)

  // * TLB 
  val tlbHitVec = entry.map(e => e.matchVAddr(io.IN_TLBReq.bits.vaddr, io.IN_VMCSR.asid))
  val tlbHit = tlbHitVec.reduce(_ || _)
  val hitIndex = OHToUInt(tlbHitVec)
  val hitEntry = entry(hitIndex)
  val pte = Mux(io.IN_TLBReq.bits.vaddr(Mux(hitEntry.PS === 21.U, 21.U, 12.U)),
    hitEntry.PPN(1), hitEntry.PPN(0))
  val isSuper = hitEntry.PS === 21.U
  val tlbPAddr = Mux(isSuper, 
    Cat(pte.PPN(19, 9), io.IN_TLBReq.bits.vaddr(20, 0)),
    Cat(pte.PPN,        io.IN_TLBReq.bits.vaddr(11, 0)))
    

  // * Faults:
  val misalign = MuxLookup(io.IN_TLBReq.bits.memLen, false.B)(Seq(
                            "b00".U -> false.B,
                            "b01".U -> io.IN_TLBReq.bits.vaddr(0,0).orR,
                            "b10".U -> io.IN_TLBReq.bits.vaddr(1,0).orR,
                            "b11".U -> false.B))
  // ** TLB Only (If DMW Hit, then no TLB Fault)
  val tlbMiss       = !dmwHit && !tlbHit
  val privFault     = !dmwHit && io.IN_VMCSR.priv > pte.PLV
  val pageFault     = !dmwHit && !pte.V
  val writeFault    = !dmwHit && io.IN_TLBReq.bits.isWrite && !pte.D

  val mmuException = WireInit(0.U.asTypeOf(new MMUException))
  mmuException.PPI := privFault
  mmuException.TLBR := tlbMiss
  when(io.IN_TLBReq.bits.isFetch) {
    mmuException.ADEF := misalign
    mmuException.PIF  := pageFault
  }.elsewhen(io.IN_TLBReq.bits.isWrite) {
    mmuException.ALE := misalign
    mmuException.PIS := pageFault
    mmuException.PME := writeFault
  }.otherwise { // Load
    mmuException.ALE := misalign
    mmuException.PIL := pageFault
  }

  val hit = dmwHit || tlbHit
  val paddr = Mux(dmwHit, dmwPAddr, tlbPAddr)
  val mat = Mux(dmwHit, dmwMAT, pte.MAT)

  io.OUT_TLBResp.bits.paddr := paddr
  io.OUT_TLBResp.bits.mat := mat

  io.OUT_TLBResp.bits.exception := mmuException

  io.OUT_TLBResp.valid := true.B

  // *
  val tlbSrchHitVec = VecInit(entry.map(e => e.matchVAddr(Cat(io.IN_TLBCSR.tlbehi.VPPN, 0.U(13.W)), io.IN_VMCSR.asid)))
  val tlbSrchIndex = OHToUInt(tlbSrchHitVec)
  val tlbSrchHit = tlbSrchHitVec.reduce(_ || _)

  // * Result of TLBOp
  // ** TLBSRCH
  io.OUT_TLBOpResult.index := tlbSrchIndex
  io.OUT_TLBOpResult.hit := tlbSrchHit
  // ** TLBRD
  io.OUT_TLBOpResult.readEntry := entry(io.IN_TLBCSR.tlbidx.TLBIDX)

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
    entry(io.IN_TLBCSR.tlbidx.TLBIDX) := wrEntry
  }.elsewhen(io.IN_TLBCtrl.fill) {
    entry(fillCounter) := wrEntry
  }

  val invMask = WireInit(0.U(NUM_TLB.W))
  when(io.IN_InvTLBOp.valid) {
    val invtlbOp = io.IN_InvTLBOp.bits
    val op = invtlbOp.op
    
    val allMask = WireInit(Fill(NUM_TLB, 1.U(1.W)))
    val G1Mask = VecInit(entry.map(e => e.G)).asUInt
    val G0Mask = VecInit(entry.map(e => !e.G)).asUInt
    val ASIDMask = VecInit(entry.map(e => e.ASID === invtlbOp.asid)).asUInt
    val VPPNMask = VecInit(entry.map(e => e.matchVPPN(invtlbOp.vppn))).asUInt

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
      entry(i).E := false.B
    }
  }
}