import chisel3._
import chisel3.util._
import utils._

class IDUIO extends CoreBundle {
  val IN_inst       = Flipped(Vec(ISSUE_WIDTH, Valid(new InstSignal)))
  val OUT_ready     = Bool()
  val OUT_decodeUop = Vec(ISSUE_WIDTH, Decoupled(new DecodeUop))
  val IN_flush    = Input(Bool())
}

class IDU extends CoreModule with HasPerfCounters {
  val io = IO(new IDUIO)

  // * Submodules
  val immgen = Seq.fill(ISSUE_WIDTH)(Module(new ImmGen))
  val decode = Seq.fill(ISSUE_WIDTH)(Module(new Decode))

  // * Main Signals
  val uopsValid = RegInit(VecInit(Seq.fill(ISSUE_WIDTH)(false.B)))
  val uopsReg = Reg(Vec(ISSUE_WIDTH, new DecodeUop))
  val uopsNext = Wire(Vec(ISSUE_WIDTH, new DecodeUop))

  for (i <- 0 until ISSUE_WIDTH) {
    // * Dataflow
    // ** Input
    val instSignal = io.IN_inst(i).bits
    val inst = instSignal.inst
    val pc = instSignal.pc
    val rd = inst(11, 7)
    val rs1 = inst(19, 15)
    val rs2 = inst(24, 20)
    // ** Output
    val uopNext = Wire(new DecodeUop)

    // *** Control Signals Generation  
    decode(i).io.inst := inst
    val decodeSignal     = decode(i).io.signals
    val illegalInst      = decodeSignal.invalid

    // *** Immediate Generation  
    immgen(i).io.inst      := inst
    immgen(i).io.inst_type := decodeSignal.immType
    val imm = immgen(i).io.imm

    // *** Filling uopNext
    uopNext.rd        := Mux(decodeSignal.regWe, rd, ZERO)
    uopNext.rs1       := Mux(decodeSignal.src1Type === SrcType.REG, rs1, 0.U)
    uopNext.rs2       := Mux(decodeSignal.src2Type === SrcType.REG, rs2, 0.U)

    uopNext.src1Type  := decodeSignal.src1Type
    uopNext.src2Type  := decodeSignal.src2Type

    uopNext.fuType    := Mux(illegalInst, FuType.FLAG,         decodeSignal.fuType)
    uopNext.opcode    := Mux(illegalInst, FlagOp.ILLEGAL_INST, decodeSignal.opcode)
    uopNext.lockBackend := decodeSignal.lockBackend
    
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

    when(instSignal.interrupt) {
      uopNext.fuType := FuType.FLAG
      uopNext.opcode := FlagOp.INTERRUPT
      uopNext.rd     := ZERO
    }.elsewhen(instSignal.pageFault) {
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

    uopsNext(i) := uopNext
  }
  
  // * Control
  // ** Input
  val inValid = VecInit(io.IN_inst.map(e => e.valid))
  val inFire = VecInit(io.IN_inst.map(e => e.fire))
  val outReady = io.OUT_decodeUop(0).ready  

  // ** IN ready generation
  val inReady = !uopsValid(0) || outReady
  io.OUT_ready := inReady

  // ** Update Logic
  when (io.IN_flush) {
    uopsValid := VecInit(Seq.fill(ISSUE_WIDTH)(false.B))
  }.elsewhen(inReady) {
    uopsValid := inValid
    uopsReg := uopsNext
  }

  // * Output Logic
  io.OUT_decodeUop.zipWithIndex.foreach { case(out, i) => 
    out.valid := uopsValid(i)
    out.bits := uopsReg(i)
  }

  // monitorEvent(iduAluInst, io.out.fire && ctrl.fuType === ALU)
  // monitorEvent(iduMemInst, io.out.fire && ctrl.fuType === MEM)
  // monitorEvent(iduBruInst, io.out.fire && ctrl.fuType === BRU)
  // monitorEvent(iduCsrInst, io.out.fire && ctrl.fuType === CSR)
}