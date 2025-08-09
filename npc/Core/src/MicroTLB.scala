import chisel3._
import chisel3.util._
import utils._

/* 
  MicroTLB serves as a cache for the MainTLB. 
  It is responsible for: 
  (1) Search DMW and its own TLB cache
  (2) Listen to MainTLB resp, replace cache
*/

class MicroTLBIO extends CoreBundle {
  val IN_TLBReq = Flipped(Valid(new TLBReq))
  val OUT_TLBResp = Valid(new TLBResp)
  
  val IN_mainTLBResp = Flipped(Valid(new MainTLBResp))

  val IN_VMCSR = Flipped(new VMCSR)
  val IN_flushMicroTLB = Flipped(Bool())
}

class TLBMissEntry extends CoreBundle{
  val valid = Bool()
  val VPPN = UInt(19.W)
  val ASID = UInt(10.W)

  def matchVAddr(vaddr: UInt, asid: UInt) = {
    valid && VPPN === vaddr(31, 13) && ASID === asid
  }
}

class MicroTLB(id: UInt, size: Int = 4) extends CoreModule {
  val io = IO(new MicroTLBIO)
  // * TLB Entries
  val entry = Reg(Vec(size, new TLBEntry))
  // * Miss Entry
  val missEntry = Reg(new TLBMissEntry)
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
  
  // * Miss Entry
  val confirmMiss = missEntry.matchVAddr(io.IN_TLBReq.bits.vaddr, io.IN_VMCSR.asid)

    // * Faults:
  val misalign = MuxLookup(io.IN_TLBReq.bits.memLen, false.B)(Seq(
                            "b00".U -> false.B,
                            "b01".U -> io.IN_TLBReq.bits.vaddr(0,0).orR,
                            "b10".U -> io.IN_TLBReq.bits.vaddr(1,0).orR,
                            "b11".U -> false.B))
  // ** TLB Only (If DMW Hit, then no TLB Fault)
  val tlbMiss       = !dmwHit && !tlbHit && confirmMiss
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
  io.OUT_TLBResp.valid := hit || confirmMiss || misalign

  // * Fill Counter
  val fillCounter = RegInit(0.U(log2Ceil(size).W))
  when(io.IN_mainTLBResp.valid && io.IN_mainTLBResp.bits.id === id) {
    when(io.IN_mainTLBResp.bits.hit) {
      // * On a MainTLB hit, update MicroTLB
      entry(fillCounter) := io.IN_mainTLBResp.bits.tlbEntry
      when(fillCounter === (size - 1).U) {
        fillCounter := 0.U
      }.otherwise {
        fillCounter := fillCounter + 1.U
      }
    }.otherwise {
      // * MainTLB Miss, update missEntry
      missEntry.ASID := io.IN_mainTLBResp.bits.asid
      missEntry.VPPN := io.IN_mainTLBResp.bits.vaddr(31, 13)
      missEntry.valid := true.B
    }
  }

  when(io.IN_flushMicroTLB) {
    entry.foreach(e => e.E := false.B)
    missEntry.valid := false.B
  }
}