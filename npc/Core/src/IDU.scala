import chisel3._
import chisel3.util._
import scala.reflect.internal.Mode
import java.util.concurrent.Future

class IDUIO extends Bundle {
  val IN_inst       = Flipped(Decoupled(new InstSignal))
  val OUT_decodeUop = Decoupled(new DecodeUop)
  val IN_flush    = Input(Bool())
}

class IDU extends Module with HasDecodeConstants with HasPerfCounters {
  val io = IO(new IDUIO)

  // * Submodules
  val immgenModule = Module(new ImmGen)
  val decodeModule = Module(new Decode)

  // * Main Signals
  val uopValid = RegInit(false.B)
  val uopReg = Reg(new DecodeUop)
  val uopNext = Wire(new DecodeUop)

  // * Dataflow
  // ** Input
  val inst = io.IN_inst.bits.inst
  val pc = io.IN_inst.bits.pc
  val rd = inst(11, 7)
  val rs1 = inst(19, 15)
  val rs2 = inst(24, 20)
    
  // *** Control Signals Generation  
  decodeModule.io.inst := io.IN_inst.bits.inst
  val decodeSignal     = decodeModule.io.signals
  val illegalInst      = decodeSignal.invalid

  // *** Immediate Generation  
  immgenModule.io.inst      := io.IN_inst.bits.inst
  immgenModule.io.inst_type := decodeSignal.immType
  val imm = immgenModule.io.imm

  // *** Filling uopNext
  uopNext.rd        := Mux(decodeSignal.regWe, rd, ZERO)
  uopNext.rs1       := Mux(decodeSignal.src1Type === REG, rs1, 0.U)
  uopNext.rs2       := Mux(decodeSignal.src2Type === REG, rs2, 0.U)

  uopNext.src1Type  := decodeSignal.src1Type
  uopNext.src2Type  := decodeSignal.src2Type

  uopNext.fuType    := Mux(illegalInst, FuType.FLAG,         decodeSignal.fuType)
  uopNext.opcode    := Mux(illegalInst, FlagOp.ILLEGAL_INST, decodeSignal.opcode)
  
  when (decodeSignal.fuType === FuType.CSR && (decodeSignal.opcode === CSROp.CSRRS || decodeSignal.opcode === CSROp.CSRRC || 
  decodeSignal.opcode === CSROp.CSRRSI || decodeSignal.opcode === CSROp.CSRRCI) && rs1 === 0.U) {
    uopNext.opcode := CSROp.CSRR
  }

  uopNext.predTarget := pc + 4.U
  uopNext.pc        := pc

  uopNext.imm       := imm
  when (decodeSignal.fuType === FuType.CSR && (decodeSignal.opcode === CSROp.CSRRWI || 
  decodeSignal.opcode === CSROp.CSRRSI || decodeSignal.opcode === CSROp.CSRRCI)) {
    uopNext.imm := Cat(rs1, imm(11, 0))
  }
  
  uopNext.compressed := false.B

  uopNext.inst      := inst

  when(io.IN_inst.bits.interrupt) {
    uopNext.fuType := FuType.FLAG
    uopNext.opcode := FlagOp.INTERRUPT
    uopNext.rd     := ZERO
  }.elsewhen(io.IN_inst.bits.pageFault) {
    uopNext.fuType := FuType.FLAG
    uopNext.opcode := FlagOp.INST_PAGE_FAULT
    uopNext.rd     := ZERO
  }.elsewhen(decodeSignal.fuType === FuType.FLAG) {
    uopNext.fuType := FuType.FLAG
    when(decodeSignal.opcode === DecodeFlagOp.NONE) {
      uopNext.opcode := FlagOp.NONE
      uopNext.rd     := ZERO
    }.otherwise{
      uopNext.opcode := FlagOp.DECODE_FLAG
      uopNext.rd     := decodeSignal.opcode      
    }
  }
  
  // * Control
  // ** Input
  val inValid = io.IN_inst.valid  
  val inFire = io.IN_inst.fire
  val outReady = io.OUT_decodeUop.ready  

  // ** IN ready generation
  val inReady = !uopValid || outReady

  // ** Update Logic
  when (io.IN_flush) {
    uopValid := false.B
  }.otherwise{
    uopValid := Mux(inReady, inValid, uopValid)  
  }  
  uopReg := Mux(inFire, uopNext, uopReg)

  // * Output Logic
  io.IN_inst.ready := inReady
  io.OUT_decodeUop.valid := uopValid
  io.OUT_decodeUop.bits := uopReg

  // monitorEvent(iduAluInst, io.out.fire && ctrl.fuType === ALU)
  // monitorEvent(iduMemInst, io.out.fire && ctrl.fuType === MEM)
  // monitorEvent(iduBruInst, io.out.fire && ctrl.fuType === BRU)
  // monitorEvent(iduCsrInst, io.out.fire && ctrl.fuType === CSR)
}