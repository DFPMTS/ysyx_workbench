import chisel3._
import chisel3.util._
import dataclass.data

trait HasFuTypes {
  def ALU = 0.U(2.W)
  def BRU = 1.U(2.W)
  def MEM = 2.U(2.W)
  def CSR = 3.U(2.W)
}

trait HasBRUOps {
  def JUMP   = 0.U(4.W)
  def BRANCH = 1.U(4.W)
}

class EXU extends Module with HasDecodeConstants {
  val io = IO(new Bundle {
    val in   = Flipped(Decoupled(new IDU_Message))
    val out  = Decoupled(new EXU_Message)
    val wb   = new WBSignal
    val dnpc = new RedirectSignal
  })
  val insert      = Wire(Bool())
  val ctrlBuffer  = RegEnable(io.in.bits.ctrl, insert)
  val dataBuffer  = RegEnable(io.in.bits.data, insert)
  val validBuffer = Reg(Bool())
  validBuffer := Mux(insert, io.in.valid, Mux(io.out.fire, false.B, validBuffer))
  val interlock = !io.out.ready || (validBuffer && ctrlBuffer.fuType === MEM)

  insert := !interlock

  io.in.ready := insert

  val ctrlOut = WireInit(ctrlBuffer)
  val dataOut = WireInit(dataBuffer)
  val wbOut   = Wire(new WBSignal)
  val dnpcOut = Wire(new RedirectSignal)

  // -------------------- Function Units ---------------------

  val aluOut        = Wire(UInt(32.W))
  val aluCmpOut     = Wire(Bool())
  val aluJumpTarget = Wire(UInt(32.W))

  val bruOut     = Wire(UInt(32.W))
  val bruPC      = Wire(UInt(32.W))
  val bruPCValid = Wire(Bool())

  val csrOut     = Wire(UInt(32.W))
  val csrPC      = Wire(UInt(32.W))
  val csrPCValid = Wire(Bool())

  // -------------------------- ALU --------------------------
  // val alu = Module(new ALU)
  // alu.io.aluFunc := ctrlBuffer.aluFunc
  // // alu.io.op1     := MuxLookup(ctrlBuffer.src1Type, 0.U)(Seq(REG -> dataBuffer.src1, PC -> dataBuffer.pc, ZERO -> 0.U))
  // // alu.io.op2     := MuxLookup(ctrlBuffer.src2Type, 0.U)(Seq(REG -> dataBuffer.src2, IMM -> dataBuffer.imm, ZERO -> 0.U))
  // alu.io.op1    := dataBuffer.src1
  // alu.io.op2    := dataBuffer.src2
  // aluOut        := alu.io.out
  // aluCmpOut     := alu.io.cmpOut
  // aluJumpTarget := alu.io.jumpTarget

  // -------------------------- BRU --------------------------
  val isBRU  = ctrlBuffer.fuType === BRU
  val isJUMP = ctrlBuffer.fuOp === JUMP

  bruOut     := dataBuffer.pc + 4.U
  bruPCValid := isJUMP || aluCmpOut
  bruPC      := Mux(isJUMP, aluJumpTarget, dataBuffer.pc + dataBuffer.imm)

  // -------------------------- CSR --------------------------
  val csr       = Module(new CSR)
  val rd        = ctrlBuffer.rd
  val rs1       = ctrlBuffer.rs1
  val isCSR     = ctrlBuffer.fuType === CSR
  val isCSRW    = isCSR && ctrlBuffer.fuOp === CSRW
  val isCSRS    = isCSR && ctrlBuffer.fuOp === CSRS
  val isECALL   = isCSR && ctrlBuffer.fuOp === ECALL
  val isMRET    = isCSR && ctrlBuffer.fuOp === MRET
  val isEBREAK  = isCSR && ctrlBuffer.fuOp === EBREAK
  val isFENCE_I = isCSR && ctrlBuffer.fuOp === FENCE_I

  // csr.io.ren   := isCSRS && rd =/= 0.U && validBuffer
  // csr.io.addr  := dataBuffer.imm(11, 0)
  // csr.io.wen   := isCSRW && rs1 =/= 0.U && validBuffer
  // csr.io.wdata := dataBuffer.src1
  // csr.io.ecall := isECALL && validBuffer
  // csr.io.pc    := dataBuffer.pc

  // csrOut     := csr.io.rdata
  // csrPCValid := isECALL || isMRET
  // csrPC      := Mux(isECALL, csr.io.mtvec, csr.io.mepc)

  val error = Module(new Error)
  error.io.ebreak       := validBuffer && isEBREAK
  error.io.access_fault := false.B
  error.io.invalid_inst := false.B
  // ---------------------------------------------------------

  ctrlOut := ctrlBuffer

  dataOut.out := Mux(isBRU, bruOut, Mux(isCSR, csrOut, aluOut))

  wbOut.rd   := ctrlBuffer.rd
  wbOut.wen  := validBuffer && ctrlBuffer.regWe && ctrlBuffer.fuType =/= MEM
  wbOut.data := dataOut.out

  dnpcOut.valid := validBuffer && ((isBRU && bruPCValid) || (isCSR && csrPCValid))
  dnpcOut.pc    := Mux(isBRU, bruPC, csrPC)

  io.out.bits.ctrl := ctrlOut
  io.out.bits.data := dataOut
  io.out.bits.dnpc := dnpcOut
  io.out.valid     := validBuffer

  io.wb   := wbOut
  io.dnpc := dnpcOut
}
