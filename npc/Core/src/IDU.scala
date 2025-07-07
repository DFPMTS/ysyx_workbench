import chisel3._
import chisel3.util._
import utils._
import FlagOp.DECODE_FLAG

class IDUIO extends CoreBundle {
  val IN_inst       = Flipped(Vec(ISSUE_WIDTH, Valid(new InstSignal)))
  val OUT_ready     = Bool()
  val OUT_decodeUop = Vec(ISSUE_WIDTH, Decoupled(new DecodeUop))
  val IN_flush    = Input(Bool())
}

class IDU extends CoreModule with HasPerfCounters {
  val io = IO(new IDUIO)

  // * Submodules
  val immgen = Seq.fill(ISSUE_WIDTH)(Module(new LA32RImmGen))
  val decode = Seq.fill(ISSUE_WIDTH)(Module(new LA32RDecode))

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
    val rd = inst(4, 0)
    val rs1 = inst(9, 5)
    val rs2 = inst(14, 10)
    // ** Output
    val uopNext = Wire(new DecodeUop)

    // *** Control Signals Generation  
    decode(i).io.inst := inst
    val decodeSignal     = decode(i).io.signals
    val illegalInst      = decodeSignal.invalid

    // *** Immediate Generation  
    immgen(i).io.inst      := inst
    immgen(i).io.inst_type := decodeSignal.immType
    val imm = WireInit(immgen(i).io.imm)

    val isBL = decodeSignal.fuType === FuType.BRU && decodeSignal.opcode === BRUOp.CALL
    val isRdcntID = decodeSignal.fuType === FuType.CSR && decodeSignal.opcode === CSROp.RDCNT_ID_W && rd === 0.U
    val isBranch = decodeSignal.fuType === FuType.BRU && BRUOp.isBranch(decodeSignal.opcode)
    val isStore = decodeSignal.fuType === FuType.LSU && LSUOp.isStore(decodeSignal.opcode)
    // *** Filling uopNext
    uopNext.rd        := Mux(decodeSignal.regWe, Mux(isBL, 1.U, Mux(isRdcntID, rs1, rd)), ZERO)
    uopNext.rs1       := Mux(decodeSignal.src1Type === SrcType.REG, rs1, 0.U)
    uopNext.rs2       := Mux(decodeSignal.src2Type === SrcType.REG, Mux(isBranch || isStore, rd, rs2), 0.U)

    uopNext.src1Type  := decodeSignal.src1Type
    uopNext.src2Type  := decodeSignal.src2Type

    uopNext.fuType    := decodeSignal.fuType
    uopNext.opcode    := decodeSignal.opcode
    uopNext.lockBackend := decodeSignal.lockBackend
    
    // when (decodeSignal.fuType === FuType.CSR && (decodeSignal.opcode === CSROp.CSRRS || decodeSignal.opcode === CSROp.CSRRC || 
    // decodeSignal.opcode === CSROp.CSRRSI || decodeSignal.opcode === CSROp.CSRRCI) && rs1 === 0.U) {
    //   uopNext.opcode := CSROp.CSRR
    // }

    uopNext.predTarget := instSignal.predTarget
    uopNext.pc        := pc

    uopNext.imm       := imm
    // when (decodeSignal.fuType === FuType.CSR && (decodeSignal.opcode === CSROp.CSRRWI || 
    // decodeSignal.opcode === CSROp.CSRRSI || decodeSignal.opcode === CSROp.CSRRCI)) {
    //   uopNext.imm := Cat(rs1, imm(11, 0))
    // }
    // when(decodeSignal.fuType === FuType.AMO) {
    //   uopNext.imm := 0.U
    // }

    uopNext.compressed := false.B

    uopNext.phtState := instSignal.phtState
    uopNext.lastBranch := instSignal.lastBranch

    uopNext.inst      := inst

    when(instSignal.interrupt) {
      uopNext.fuType := FuType.FLAG
      uopNext.opcode := FlagOp.DECODE_FLAG
      uopNext.rd     := DecodeFlagOp.INTERRUPT
    }.elsewhen(instSignal.addrMisalign) {
      uopNext.fuType := FuType.FLAG
      uopNext.opcode := FlagOp.ADEF
    }.elsewhen(instSignal.tlbMiss){
      uopNext.fuType := FuType.FLAG
      uopNext.opcode := FlagOp.TLBR
    }.elsewhen(instSignal.pageFault) {
      uopNext.fuType := FuType.FLAG
      uopNext.opcode := FlagOp.PIF
    }.elsewhen(instSignal.pagePrivFail){
      // * Fetch PPI
      uopNext.fuType := FuType.FLAG
      uopNext.opcode := FlagOp.DECODE_FLAG
      uopNext.rd     := DecodeFlagOp.PPI
    }.elsewhen(instSignal.access_fault) {
      // * Fetch ADEF
      uopNext.fuType := FuType.FLAG
      uopNext.opcode := FlagOp.ADEF
    }.elsewhen(illegalInst) {
      uopNext.fuType := FuType.FLAG
      uopNext.opcode := FlagOp.INE
    }.elsewhen(decodeSignal.fuType === FuType.FLAG) {
      uopNext.fuType := FuType.FLAG
      when(decodeSignal.opcode === DecodeFlagOp.NONE) {
        uopNext.opcode := FlagOp.NONE
        uopNext.rd     := ZERO
      }.elsewhen(decodeSignal.opcode === DecodeFlagOp.BRK) {
        uopNext.opcode := FlagOp.BRK
      }.elsewhen(decodeSignal.opcode === DecodeFlagOp.SYS) {
        uopNext.opcode := FlagOp.SYS
      }.otherwise {
        uopNext.opcode := DECODE_FLAG
        uopNext.rd := decodeSignal.opcode
      }
    }.elsewhen(decodeSignal.fuType === FuType.BRU) {
      val rawBRUOp = decodeSignal.opcode
      val bruOp = WireDefault(rawBRUOp)
      def isLinkReg(regNum: UInt): Bool = {
        regNum === 1.U
      }
      when(rawBRUOp === BRUOp.JALR) {
        val rdLink = isLinkReg(rd)
        val rs1Link = isLinkReg(rs1)
        val rdEqRs1 = rd === rs1
        when(rdLink && rs1Link) {
          bruOp := BRUOp.CALL
          // ! Not correct!
          // * See Table 3. Return-address Stack Prediction hints 
          // * encoded in the register operands of a JALR instruction
        } .elsewhen(rdLink && !rs1Link) {
          bruOp := BRUOp.CALL
        } .elsewhen(!rdLink && rs1Link) {
          bruOp := BRUOp.RET
        }
      }
      uopNext.opcode := bruOp
    }.elsewhen(decodeSignal.fuType === FuType.CSR) {
      when(decodeSignal.opcode === CSROp.CSRXCHG) {
        when(rs1 === 0.U) {
          uopNext.opcode := CSROp.CSRRD
        }.elsewhen(rs1 === 1.U) {
          uopNext.opcode := CSROp.CSRWR
          uopNext.rs1 := rd
        }.otherwise {
          uopNext.rs1 := rd
          uopNext.rs2 := rs1
        }
      }.elsewhen(decodeSignal.opcode === CSROp.RDCNT_ID_W) {
        when(rd === 0.U) {
          uopNext.opcode := CSROp.RDCNT_ID_W
        }.elsewhen(rs1 === 0.U) {
          uopNext.opcode := CSROp.RDCNT_VL_W
        }
      }.elsewhen(decodeSignal.opcode === CSROp.INVTLB) {
        when(imm(4, 0) > 0x6.U){
          uopNext.fuType := FuType.FLAG
          uopNext.opcode := FlagOp.INE
        }
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
}