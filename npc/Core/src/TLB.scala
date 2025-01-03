import chisel3._
import chisel3.util._
import utils._

class TLBIO extends CoreBundle {
  val IN_TLBReq = Flipped(Valid(new TLBReq))
  val OUT_TLBResp = Valid(new TLBResp)
  val IN_PTWResp = Flipped(Valid(new PTWResp))
  
  val IN_TLBFlush = Flipped(Bool())
}


class TLBEntry extends CoreBundle{  
  val valid = Bool()
  val vpn = UInt(PAGE_NR_LEN.W)
  val pte = PTE()  
  val isSuper = Bool()
}

class TLB(size: Int, id: Int) extends CoreModule {
  val io = IO(new TLBIO)

  val counter = Counter(size)

  val entry = RegInit(VecInit(Seq.fill(size)({
    0.U.asTypeOf(new TLBEntry)
  })))

  val hitVec = entry.map(e => e.valid && e.vpn === io.IN_TLBReq.bits.vpn)
  val hit = hitVec.reduce(_ || _)
  val hitIndex = OHToUInt(hitVec)

  val hitEntry = entry(hitIndex)
  io.OUT_TLBResp.bits.isSuper := hitEntry.isSuper
  io.OUT_TLBResp.bits.pte := hitEntry.pte
  io.OUT_TLBResp.valid := io.IN_TLBReq.valid && hit
  when(io.OUT_TLBResp.valid){
    when(counter.value === hitIndex){
      counter.inc()
    }
  }

  when(io.IN_PTWResp.valid && io.IN_PTWResp.bits.id === id.U){
    entry(counter.value).valid := true.B
    entry(counter.value).vpn := io.IN_PTWResp.bits.vpn
    entry(counter.value).pte := io.IN_PTWResp.bits.pte
    entry(counter.value).isSuper := io.IN_PTWResp.bits.isSuper
  }

  when(io.IN_TLBFlush) {
    entry.foreach(e => e.valid := false.B)
  }
}