import chisel3._
import chisel3.util._
import utils._

class testRenameIO extends CoreBundle {
  val inst = Input(UInt(32.W))
  val valid = Input(Bool())
  val renameUop = Output(Vec(ISSUE_WIDTH, Valid(new RenameUop)))
}

class testRename extends CoreModule {
  val io = IO(new testRenameIO)

  val IDU = Module(new IDU)
  val rename = Module(new Rename)

  IDU.io.IN_inst.bits.inst := io.inst
  IDU.io.IN_inst.bits.pc := 0.U
  IDU.io.IN_inst.bits.access_fault := false.B
  IDU.io.IN_inst.valid := io.valid

  IDU.io.IN_flush := false.B

  rename.io.IN_decodeUop(0) <> IDU.io.OUT_decodeUop

  rename.io.IN_flush := false.B
  rename.io.IN_writebackUop.foreach(x => {
    x.valid := false.B
    x.bits := 0.U.asTypeOf(new WritebackUop)
  })
  rename.io.IN_commitUop.foreach(x => {
    x.valid := false.B
    x.bits := 0.U.asTypeOf(new CommitUop)
  })

  rename.io.IN_issueQueueReady.foreach(x => {
    x := true.B
  })
  rename.io.IN_robReady := true.B
  io.renameUop(0).bits := rename.io.OUT_renameUop(0) 
  io.renameUop(0).valid := rename.io.OUT_robValid(0)
}