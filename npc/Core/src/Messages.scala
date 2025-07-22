import chisel3._
import chisel3.util._
import chisel3.experimental.dataview._

object Config {
  val XLEN         = 32
  var debug        = true
  var resetPC      = "h1c000000".U
  val eventIdWidth = 6.W
  var target       = "npc_top"
}

trait HasDecodeConstants
    extends HasInstType
    with HasFuTypes
    with HasBRUOps
    with HasCSROps
    with HasALUFuncs
    with HasLSUOps {
  def N    = 0.U(1.W)
  def Y    = 1.U(1.W)
  def X    = BitPat("b?")
  def ZERO = 0.U(2.W)
  def REG  = 1.U(2.W)
  def IMM  = 2.U(2.W)
  def PC   = 3.U(2.W)
  def OP_X = BitPat("b????")
}

trait HasPerfCounters {
  // IFU
  def ifuFinished = 0.U(Config.eventIdWidth)
  def ifuStalled  = 1.U(Config.eventIdWidth)

  // IDU
  def iduBruInst = 2.U(Config.eventIdWidth)
  def iduAluInst = 3.U(Config.eventIdWidth)
  def iduMemInst = 4.U(Config.eventIdWidth)
  def iduCsrInst = 5.U(Config.eventIdWidth)

  // EXU

  // MEM
  def memFinished = 6.U(Config.eventIdWidth)
  def memStalled  = 7.U(Config.eventIdWidth)

  // WBU
  // should count committed instructions types

  // ICache
  def icacheMiss = 8.U(Config.eventIdWidth)

  def totalBranch = 9.U(Config.eventIdWidth)
  def branchMisPred = 10.U(Config.eventIdWidth)

  def monitorEvent(eventId: UInt, enable: Bool) = {
    if (Config.debug) {
      val monitor = Module(new EventMonitor)
      monitor.io.eventId := eventId
      monitor.io.enable  := enable
    }
  }
}

class PC_Message extends Bundle {
  val pc = UInt(32.W)
}

class InstSignal extends Bundle {
  val pc           = UInt(32.W)
  val predTarget   = UInt(32.W)
  val inst         = UInt(32.W)
  val addrMisalign = Bool()
  val access_fault = Bool()
  val pageFault    = Bool()
  val interrupt    = Bool()
  val tlbMiss      = Bool()
  val pagePrivFail = Bool()
  val phtState     = new SaturatedCounter
  val lastBranch   = Bool()
}

class ControlSignal extends Bundle {
  val inst = UInt(32.W)

  val invalid  = Bool()
  val regWe    = Bool()
  val src1Type = UInt(2.W)
  val src2Type = UInt(2.W)
  val aluFunc  = UInt(4.W)
  val fuType   = UInt(2.W)
  val fuOp     = UInt(4.W)

  val rs1 = UInt(4.W)
  val rs2 = UInt(4.W)
  val rd  = UInt(4.W)
}

class DataSignal extends Bundle {
  val src1   = UInt(32.W)
  val src2   = UInt(32.W)
  val pc     = UInt(32.W)
  val imm    = UInt(32.W)
  val rs2Val = UInt(32.W)
  val out    = UInt(32.W)
}

class RedirectSignal extends Bundle {
  val valid = Bool()
  val pc    = UInt(32.W)
}

class WBSignal extends Bundle {
  val wen  = Bool()
  val rd   = UInt(4.W)
  val data = UInt(32.W)
  def tryBypass(rs: UInt, rsVal: UInt) = {
    Mux(wen && rd === rs && rs =/= 0.U, data, rsVal)
  }
}

class IDU_Message extends Bundle {
  val ctrl = new ControlSignal
  val data = new DataSignal
}

class EXU_Message extends Bundle {
  val ctrl = new ControlSignal
  val data = new DataSignal
  val dnpc = new RedirectSignal
}

class MEM_Message extends Bundle {
  val ctrl = new ControlSignal
  val data = new DataSignal
  val dnpc = new RedirectSignal
}

class WBU_Message extends Bundle {
  val wb   = new WBSignal
  val dnpc = new RedirectSignal
}

class AXI_Lite extends Bundle {
  val ar = Decoupled(new Bundle {
    val addr = UInt(32.W)
  })

  val r = Flipped(Decoupled(new Bundle {
    val data = UInt(32.W)
    val resp = UInt(2.W)
  }))

  val aw = Decoupled(new Bundle {
    val addr = UInt(32.W)
  })

  val w = Decoupled(new Bundle {
    val data = UInt(32.W)
    val strb = UInt(4.W)
  })

  val b = Flipped(Decoupled(new Bundle {
    val resp = UInt(2.W)
  }))
}

class AXI4(val dataBits: Int, val addrBits: Int) extends Bundle {

  val aw = Decoupled(new Bundle {
    val addr  = UInt(addrBits.W)
    val id    = UInt(4.W) // tied to LOW
    val len   = UInt(8.W) // tied to 0b00
    val size  = UInt(3.W)
    val burst = UInt(2.W) // tied to 0b01
  })

  val w = Decoupled(new Bundle {
    val id   = UInt(4.W)
    val data = UInt(dataBits.W)
    val strb = UInt((dataBits / 8).W)
    val last = Bool()
  })

  val b = Flipped(Decoupled(new Bundle {
    val resp = UInt(2.W)
    val id   = UInt(4.W) // tied to LOW
  }))

  val ar = Decoupled(new Bundle {
    val addr  = UInt(addrBits.W)
    val id    = UInt(4.W) // tied to LOW
    val len   = UInt(8.W) // tied to 0b00
    val size  = UInt(3.W)
    val burst = UInt(2.W) // tied to 0b01
  })

  val r = Flipped(Decoupled(new Bundle {
    val resp = UInt(2.W)
    val data = UInt(dataBits.W)
    val last = Bool()
    val id   = UInt(4.W) // tied to LOW
  }))

}

class AXI4ysyxSoC(val dataBits: Int, val addrBits: Int) extends Bundle {
  val awready = Input(Bool())
  val awvalid = Output(Bool())
  val awaddr  = Output(UInt(addrBits.W))
  val awid    = Output(UInt(4.W))
  val awlen   = Output(UInt(8.W))
  val awsize  = Output(UInt(3.W))
  val awburst = Output(UInt(2.W))
  val wready  = Input(Bool())
  val wvalid  = Output(Bool())
  val wid     = Output(UInt(4.W))
  val wdata   = Output(UInt(dataBits.W))
  val wstrb   = Output(UInt((dataBits / 8).W))
  val wlast   = Output(Bool())
  val bready  = Output(Bool())
  val bvalid  = Input(Bool())
  val bresp   = Input(UInt(2.W))
  val bid     = Input(UInt(4.W))
  val arready = Input(Bool())
  val arvalid = Output(Bool())
  val araddr  = Output(UInt(addrBits.W))
  val arid    = Output(UInt(4.W))
  val arlen   = Output(UInt(8.W))
  val arsize  = Output(UInt(3.W))
  val arburst = Output(UInt(2.W))
  val rready  = Output(Bool())
  val rvalid  = Input(Bool())
  val rresp   = Input(UInt(2.W))
  val rdata   = Input(UInt(dataBits.W))
  val rlast   = Input(Bool())
  val rid     = Input(UInt(4.W))
}

object AXI4 {
  implicit val axiView = DataView[AXI4ysyxSoC, AXI4](
    vab => new AXI4(vab.dataBits, vab.addrBits),
    _.awready -> _.aw.ready,
    _.awvalid -> _.aw.valid,
    _.awaddr -> _.aw.bits.addr,
    _.awid -> _.aw.bits.id,
    _.awlen -> _.aw.bits.len,
    _.awsize -> _.aw.bits.size,
    _.awburst -> _.aw.bits.burst,
    _.wready -> _.w.ready,
    _.wvalid -> _.w.valid,
    _.wdata -> _.w.bits.data,
    _.wid -> _.w.bits.id,
    _.wstrb -> _.w.bits.strb,
    _.wlast -> _.w.bits.last,
    _.bready -> _.b.ready,
    _.bvalid -> _.b.valid,
    _.bresp -> _.b.bits.resp,
    _.bid -> _.b.bits.id,
    _.arready -> _.ar.ready,
    _.arvalid -> _.ar.valid,
    _.araddr -> _.ar.bits.addr,
    _.arid -> _.ar.bits.id,
    _.arlen -> _.ar.bits.len,
    _.arsize -> _.ar.bits.size,
    _.arburst -> _.ar.bits.burst,
    _.rready -> _.r.ready,
    _.rvalid -> _.r.valid,
    _.rresp -> _.r.bits.resp,
    _.rdata -> _.r.bits.data,
    _.rlast -> _.r.bits.last,
    _.rid -> _.r.bits.id
  )

  implicit val axiView2 = axiView.invert(ab => new AXI4ysyxSoC(ab.dataBits, ab.addrBits))
}
