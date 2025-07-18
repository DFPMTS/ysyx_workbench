import chisel3._
import chisel3.util._
import chisel3.experimental.dataview._

class npc_top_io extends Bundle {
  val hwIntr = Input(UInt(8.W))
}

class npc_top extends Module {
  val io = IO(new npc_top_io)

  val npc  = Module(new Core)
  val sram = Module(new SRAM)

  npc.io.hwIntr := io.hwIntr
  npc.io.master.viewAs[AXI4] <> sram.io
  dontTouch(npc.io.master)
  // npc.io.interrupt     := false.B
  // npc.io.slave.awvalid := false.B
  // npc.io.slave.awaddr  := 0.U
  // npc.io.slave.awlen   := 0.U
  // npc.io.slave.awsize  := 0.U
  // npc.io.slave.awburst := "b01".U
  // npc.io.slave.awid    := 0.U

  // npc.io.slave.wvalid := false.B
  // npc.io.slave.wdata  := 0.U
  // npc.io.slave.wstrb  := 0.U
  // npc.io.slave.wlast  := true.B

  // npc.io.slave.bready := false.B

  // npc.io.slave.arvalid := false.B
  // npc.io.slave.araddr  := 0.U
  // npc.io.slave.arlen   := 0.U
  // npc.io.slave.arid    := 0.U
  // npc.io.slave.arsize  := 0.U
  // npc.io.slave.arburst := "b01".U

  // npc.io.slave.rready := false.B
}
