import chisel3._
import chisel3.util._
import utils._
import os.stat

trait HasLSUOps {
  def U    = 0.U(1.W)
  def S    = 1.U(1.W)
  def BYTE = 0.U(2.W)
  def HALF = 1.U(2.W)
  def WORD = 2.U(2.W)
  def R    = 0.U(1.W)
  def W    = 1.U(1.W)

  def LB  = BitPat("b0000")
  def LBU = BitPat("b0001")

  def LH  = BitPat("b0010")
  def LHU = BitPat("b0011")

  def LW = BitPat("b0100")

  def SB = BitPat("b1000")

  def SH = BitPat("b1010")

  def SW = BitPat("b1100")
}

class LSUIO extends CoreBundle {
  val IN_AGUUop = Flipped(Decoupled(new AGUUop))
  val OUT_writebackUop = Valid(new WritebackUop)
  val master = new AXI4(32, 32)
}

class LSU extends CoreModule with HasLSUOps {
  val io = IO(new LSUIO)

  val amoALU = Module(new AMOALU)

  val sIdle :: sWaitResp :: sWaitAmoSave :: Nil = Enum(3)
  val state = RegInit(sIdle)

  val respValid = io.master.r.fire || io.master.b.fire
  val insert1 = (state === sIdle && io.IN_AGUUop.valid)
  val insert2 = (state === sWaitResp && respValid)
  val insert = insert1 || insert2
  val inUop = RegEnable(io.IN_AGUUop.bits, insert1)
  val opcode = inUop.opcode
  
  io.IN_AGUUop.ready := state === sIdle

  state := MuxLookup(state, sIdle)(
    Seq(
      sIdle -> Mux(io.IN_AGUUop.valid, sWaitResp, sIdle),
      sWaitResp -> Mux(respValid, Mux(
        inUop.fuType === FuType.LSU, sIdle, sWaitAmoSave), sWaitResp),
      sWaitAmoSave -> Mux(io.master.b.fire, sIdle, sWaitAmoSave)
    )
  )

  val uopRead  = state === sWaitResp && ((inUop.fuType === FuType.LSU && opcode(3) === R) || inUop.fuType === FuType.AMO)
  val uopWrite = (state === sWaitResp && (inUop.fuType === FuType.LSU && opcode(3) === W)) || 
                 (state === sWaitAmoSave && inUop.fuType === FuType.AMO)

  val memLen     = Mux(inUop.fuType === FuType.LSU, opcode(2, 1), 2.U)
  val loadU      = opcode(0)

  val addr        = inUop.addr
  val addr_offset = addr(1, 0)

  // ar_valid/aw_valid/w_valid 当一个valid请求进入时置为true,在相应通道握手后为false
  val ar_valid = RegInit(false.B)
  ar_valid := Mux(
    insert,
    true.B,
    Mux(io.master.ar.fire, false.B, ar_valid)
  )
  io.master.ar.valid      := ar_valid && uopRead
  io.master.ar.bits.addr  := addr
  io.master.ar.bits.id    := 0.U
  io.master.ar.bits.len   := 0.U
  io.master.ar.bits.size  := memLen
  io.master.ar.bits.burst := "b01".U

  val rdata = io.master.r.bits.data
  io.master.r.ready := true.B

  amoALU.io.IN_src1 := rdata
  amoALU.io.IN_src2 := inUop.wdata
  amoALU.io.IN_opcode := opcode

  val aw_valid = RegInit(false.B)
  aw_valid := Mux(
    insert,
    true.B,
    Mux(io.master.aw.fire, false.B, aw_valid)
  )
  io.master.aw.valid      := aw_valid && uopWrite
  io.master.aw.bits.addr  := addr
  io.master.aw.bits.id    := 0.U
  io.master.aw.bits.len   := 0.U
  io.master.aw.bits.size  := memLen
  io.master.aw.bits.burst := "b01".U

  val w_valid = RegInit(false.B)
  w_valid := Mux(
    insert,
    true.B,
    Mux(io.master.w.fire, false.B, w_valid)
  )
  val wData = Mux(
    state === sWaitResp,
    inUop.wdata << (addr_offset << 3.U),
    amoALU.io.OUT_res
  )
  io.master.w.valid     := w_valid && uopWrite
  io.master.w.bits.data := wData
  io.master.w.bits.strb := MuxLookup(memLen, 0.U(4.W))(
    Seq(
      0.U(2.W) -> "b0001".U,
      1.U(2.W) -> "b0011".U,
      2.U(2.W) -> "b1111".U
    )
  ) << addr_offset
  io.master.w.bits.last := true.B

  io.master.b.ready := true.B

  val raw_data      = rdata >> (addr_offset << 3.U)
  val sign_ext_data = WireDefault(raw_data)
  when(memLen === BYTE) {
    sign_ext_data := Cat(Fill(24, ~loadU & raw_data(7)), raw_data(7, 0))
  }.elsewhen(memLen === HALF) {
    sign_ext_data := Cat(Fill(16, ~loadU & raw_data(15)), raw_data(15, 0))
  }

  val uop = Reg(new WritebackUop)
  val uopValid = RegInit(false.B)
  
  uopValid := state === sWaitResp && respValid
  
  uop.data := sign_ext_data
  uop.prd := inUop.prd
  uop.robPtr := inUop.robPtr
  uop.flag := 0.U
  uop.target := 0.U
  uop.dest := inUop.dest

  io.OUT_writebackUop.bits := uop
  io.OUT_writebackUop.valid := uopValid
}

class AMOALUIO extends CoreBundle {
  val IN_src1 = Flipped(UInt(XLEN.W))
  val IN_src2 = Flipped(UInt(XLEN.W))
  val IN_opcode = Flipped(UInt(OpcodeWidth.W))
  val OUT_res = UInt(XLEN.W)
}

class AMOALU extends CoreModule {
  val io = IO(new AMOALUIO)

  val src1 = io.IN_src1
  val src2 = io.IN_src2
  val opcode = io.IN_opcode

  val res = Wire(UInt(XLEN.W))
  res := 0.U

  res := MuxLookup(opcode, src2)(
    Seq(      
      AMOOp.SWAP_W -> src2,
      AMOOp.ADD_W  -> (src1 + src2),
      AMOOp.AND_W  -> (src1 & src2),
      AMOOp.OR_W   -> (src1 | src2),
      AMOOp.XOR_W  -> (src1 ^ src2),
      AMOOp.MIN_W  -> Mux(src1.asSInt < src2.asSInt, src1, src2),
      AMOOp.MAX_W  -> Mux(src1.asSInt > src2.asSInt, src1, src2),
      AMOOp.MINU_W -> Mux(src1 < src2, src1, src2),
      AMOOp.MAXU_W -> Mux(src1 > src2, src1, src2)      
    )
  )

  io.OUT_res := res
}