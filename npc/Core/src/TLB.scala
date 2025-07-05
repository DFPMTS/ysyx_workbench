import chisel3._
import chisel3.util._
import utils._

class TLBIO extends CoreBundle {
  val IN_TLBReq = Flipped(Valid(new TLBReq))
  val OUT_TLBResp = Valid(new TLBResp)
  val IN_PTWResp = Flipped(Valid(new PTWResp))

  val IN_VMCSR = Flipped(new VMCSR)
  
  val IN_TLBFlush = Flipped(Bool())
}

class TLBPPN extends CoreBundle {
  val PPN  = UInt(20.W)
  val PLV  = UInt(2.W)
  val MAT  = UInt(2.W)
  val D    = Bool()
  val V    = Bool()
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
    val vaMatch = Mux(is21,
      vaddr(31, 22) === VPPN(18, 9),
      vaddr(31, 13) === VPPN,
    )

    val asidMatch = G || (asid === ASID)

    (vaMatch && asidMatch)
  }
}

class TLB(id: Int) extends CoreModule {
  val io = IO(new TLBIO)

  val counter = Counter(32)

  val entry = RegInit(VecInit(Seq.fill(32)({
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
}