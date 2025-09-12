import chisel3._
import chisel3.util._
import chisel3.experimental.dataview._

class multi extends Module {
  val io = IO(new Bundle {
    val master    = new AXI4ysyxSoC(32, 32)
    val slave     = Flipped(new AXI4ysyxSoC(32, 32))
    val interrupt = Input(Bool())
  })

  // val ifu = Module(new IFU)
  // val idu = Module(new IDU)
  // val exu = Module(new EXU)
  // val mem = Module(new MEM)

  val arbiter = Module(new AXI_Arbiter)

  // val EXtoIF = Wire(new RedirectSignal)

  // // IF
  // ifu.io.redirect := EXtoIF
  // ifu.io.master <> arbiter.io.IFUMaster

  // // DE
  // idu.io.in <> ifu.io.out

  // // EX
  // exu.io.in <> idu.io.out
  // exu.io.wb <> idu.io.EXBypass
  // EXtoIF        := exu.io.dnpc
  // idu.io.flush  := exu.io.dnpc.valid
  // mem.io.inDnpc := exu.io.dnpc

  // // MEM
  // mem.io.in <> exu.io.out
  // mem.io.master <> arbiter.io.LSUMaster
  // mem.io.wb <> idu.io.wb  

  // val commitHelper = Module(new CommitHelper);
  // commitHelper.io.commit := mem.io.valid

  // if (Config.debug) {
  //   val valid  = RegNext(mem.io.valid)
  //   val archPC = RegInit(UInt(32.W), Config.resetPC)
  //   archPC := Mux(mem.io.valid, Mux(mem.io.outDnpc.valid, mem.io.outDnpc.pc, archPC + 4.U), archPC)
  //   dontTouch(valid)
  //   dontTouch(archPC)
  // }

  // ifu.io.flushICache := false.B

  arbiter.io.winMaster.viewAs[AXI4ysyxSoC] <> io.master

  // // AXI4 slave
  // io.slave.awready := false.B
  // io.slave.arready := false.B
  // io.slave.wready  := false.B

  // io.slave.bvalid := false.B
  // io.slave.bresp  := 0.U
  // io.slave.bid    := 0.U

  // io.slave.rdata  := 0.U
  // io.slave.rvalid := false.B
  // io.slave.rresp  := 0.U
  // io.slave.rid    := 0.U
  // io.slave.rlast  := true.B
}
