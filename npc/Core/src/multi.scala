import chisel3._
import chisel3.util._
import chisel3.experimental.dataview._

class top extends Module {
  val io = IO(new Bundle {
    // val master = new AXI4ysyxSoC(64, 32)
  })

  val ifu     = Module(new IFU)
  val idu     = Module(new IDU)
  val exu     = Module(new EXU)
  val wbu     = Module(new WBU)
  val regfile = Module(new RegFile)

  val pc = RegInit(UInt(32.W), "h80000000".U)

  val pc_valid = RegInit(true.B)
  when(ifu.io.in.fire) {
    pc_valid := false.B
  }

  val arbiter = Module(new AXI_Arbiter)

  // IF
  val sram = Module(new SRAM)
  arbiter.io.out_slave <> sram.io
  ifu.io.in.bits.pc := Mux(wbu.io.out.valid, wbu.io.out.bits.dnpc, pc)
  ifu.io.in.valid   := pc_valid && !reset.asBool
  ifu.io.master <> arbiter.io.IFU_master

  // DE
  idu.io.in <> ifu.io.out
  val idu_out     = idu.io.out.bits
  val idu_message = Wire(new IDU_Message)
  idu_message.ctrl := idu_out.ctrl
  idu_message.imm  := idu_out.imm
  idu_message.inst := idu_out.inst
  idu_message.pc   := idu_out.pc

  val idu_inst = idu.io.out.bits.inst
  regfile.io.rs1_sel := idu_inst(19, 15)
  regfile.io.rs2_sel := idu_inst(24, 20)
  idu_message.rs1    := regfile.io.rs1
  idu_message.rs2    := regfile.io.rs2

  // EX
  exu.io.in.bits   := idu_message
  exu.io.in.valid  := idu.io.out.valid
  idu.io.out.ready := exu.io.in.ready
  exu.io.master <> arbiter.io.EXU_master

  // WB
  wbu.io.in <> exu.io.out
  wbu.io.out.ready   := true.B
  regfile.io.wb_data := wbu.io.out.bits.wb_data
  regfile.io.wr_sel  := wbu.io.out.bits.inst(11, 7)
  regfile.io.reg_we  := false.B

  val ebreak = Module(new EBREAK)
  ebreak.io.ebreak := 0.U
  when(wbu.io.out.valid) {
    pc_valid          := true.B
    pc                := wbu.io.out.bits.dnpc
    regfile.io.reg_we := wbu.io.out.bits.reg_we
    ebreak.io.ebreak  := wbu.io.out.bits.ebreak
  }
  val commit_inst = RegNext(wbu.io.out.bits.inst)
  dontTouch(commit_inst)
  // arbiter.io.out_slave.viewAs[AXI4ysyxSoC] <> io.master
}
