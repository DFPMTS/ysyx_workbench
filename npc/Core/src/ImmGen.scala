import chisel3._
import chisel3.util._
import java.util.function.BiPredicate
import utils.CoreModule

trait HasInstType {
  def IMM_I = 0.U(3.W)
  def IMM_U = 1.U(3.W)
  def IMM_S = 2.U(3.W)
  def IMM_B = 3.U(3.W)
  def IMM_J = 4.U(3.W)
  def IMM_R = BitPat("b???")
  def IMM_X = BitPat("b???")
}

class ImmGen extends Module with HasInstType {
  val io = IO(new Bundle {
    val inst_type = Input(UInt(3.W));
    val inst      = Input(UInt(32.W));
    val imm       = Output(UInt(32.W))
  })

  val inst = io.inst;
  val immI = Wire(SInt(32.W))
  val immU = Wire(SInt(32.W))
  val immS = Wire(SInt(32.W))
  val immB = Wire(SInt(32.W))
  val immJ = Wire(SInt(32.W))

  immI := Cat(inst(31, 20)).asSInt
  immU := Cat(inst(31, 12), 0.U(12.W)).asSInt
  immS := Cat(inst(31, 25), inst(11, 7)).asSInt
  immB := Cat(inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W)).asSInt
  immJ := Cat(inst(31), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W)).asSInt
  io.imm := MuxLookup(io.inst_type, immI)(
    Seq(IMM_I -> immI, IMM_U -> immU, IMM_S -> immS, IMM_B -> immB, IMM_J -> immJ)
  ).asUInt
}

/* object LA32RImmType extends HasDecodeConfig {
  def UI5 = 0.U(ImmTypeWidth.W) 
  def UI12 = 1.U(ImmTypeWidth.W)
  def SI12 = 2.U(ImmTypeWidth.W)
  def SI14 = 3.U(ImmTypeWidth.W)
  def SI20 = 4.U(ImmTypeWidth.W)
  def OFFS16 = 5.U(ImmTypeWidth.W)
  def OFFS26 = 6.U(ImmTypeWidth.W)
  def CSR    = 7.U(ImmTypeWidth.W)
  def X = BitPat.dontCare(ImmTypeWidth)
}
 */
class LA32RImmGen extends CoreModule {
  val io = IO(new Bundle {
    val inst_type = Input(UInt(3.W));
    val inst      = Input(UInt(32.W));
    val imm       = Output(UInt(32.W))
  })

  val inst = io.inst;
  val ui5 = Wire(UInt(32.W))
  val si5 = Wire(SInt(32.W))
  val si12 = Wire(SInt(32.W))
  val ui12 = Wire(UInt(32.W))
  val si14 = Wire(SInt(32.W))
  val si20 = Wire(SInt(32.W))
  val offs16 = Wire(SInt(32.W))
  val offs26 = Wire(SInt(32.W))
  val csr = Wire(UInt(32.W))
  ui5 := inst(14, 10)
  si5 := inst(14, 10).asSInt
  si12 := inst(21, 10).asSInt
  ui12 := inst(21, 10)
  si14 := inst(23, 10).asSInt
  si20 := Cat(inst(24, 5), 0.U(12.W)).asSInt
  offs16 := Cat(inst(25, 10), 0.U(2.W)).asSInt
  offs26 := Cat(inst(9, 0), inst(25, 10), 0.U(2.W)).asSInt
  csr := inst(23, 10)

  io.imm := MuxLookup(io.inst_type, ui5)(
    Seq(
      LA32RImmType.UI5 -> ui5,
      LA32RImmType.UI12 -> ui12,
      LA32RImmType.SI12 -> si12.asUInt,      
      LA32RImmType.SI14 -> si14.asUInt, 
      LA32RImmType.SI20 -> si20.asUInt,
      LA32RImmType.OFFS16 -> offs16.asUInt,
      LA32RImmType.OFFS26 -> offs26.asUInt,
      LA32RImmType.CSR -> csr
    )
  )
}