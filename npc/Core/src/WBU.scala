import chisel3._
import chisel3.util._
import dataclass.data
import Config.debug

trait HasCSROps {
  def CSRW    = 0.U(4.W)
  def CSRS    = 1.U(4.W)
  def ECALL   = 2.U(4.W)
  def MRET    = 3.U(4.W)
  def EBREAK  = 4.U(4.W)
  def FENCE_I = 5.U(4.W)
}

class WBU extends Module with HasDecodeConstants {
  val io = IO(new Bundle {
    val in          = Flipped(Decoupled(new MEM_Message))
    val wb          = new WBSignal
    val dnpc        = new RedirectSignal
    val valid       = Output(Bool())
    val flushICache = Output(Bool())
  })
  val validBuffer = RegNext(io.in.valid)
  val dnpcBuffer  = RegNext(io.in.bits.dnpc)
  val ctrlBuffer  = RegNext(io.in.bits.ctrl)
  val dataBuffer  = RegNext(io.in.bits.data)

  io.in.ready := true.B

  val csrOut     = Wire(UInt(32.W))
  val csrPC      = Wire(UInt(32.W))
  val csrPCValid = Wire(Bool())

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
  // csr.io.wdata := dataBuffer.out
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

  io.wb.wen  := validBuffer && ctrlBuffer.regWe
  io.wb.data := Mux(isCSR, csrOut, dataBuffer.out)
  io.wb.rd   := ctrlBuffer.rd

  io.dnpc.valid := validBuffer && Mux(isCSR, csrPCValid, dnpcBuffer.valid)
  io.dnpc.pc    := Mux(isCSR, csrPC, dnpcBuffer.pc)

  io.valid       := validBuffer
  io.flushICache := validBuffer && isFENCE_I

  if (Config.debug) {
    dontTouch(ctrlBuffer)
    dontTouch(dataBuffer)
  }
}
