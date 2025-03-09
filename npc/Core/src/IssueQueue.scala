import chisel3._
import chisel3.util._
import utils._
import org.fusesource.jansi.internal.Kernel32.FOCUS_EVENT_RECORD

class IssueQueueIO extends CoreBundle {
  val IN_renameUop = Flipped(Decoupled(new RenameUop))
  val IN_writebackUop = Flipped(Vec(MACHINE_WIDTH, Valid(new WritebackUop)))
  val OUT_issueUop = Decoupled(new RenameUop)
  val IN_robTailPtr = Input(RingBufferPtr(ROB_SIZE))
  val IN_stqBasePtr = Flipped(RingBufferPtr(STQ_SIZE))
  val IN_ldqBasePtr = Flipped(RingBufferPtr(LDQ_SIZE))
  val IN_flush = Input(Bool())

  val IN_idivBusy = Input(Bool())
}

class IssueQueue(FUs: Seq[UInt]) extends CoreModule {
  val io = IO(new IssueQueueIO)

  def hasFU(fu: UInt) = {
    FUs.contains(fu)
  }

  def isFuType(uop: RenameUop, fu: UInt) = {
    uop.fuType === fu
  }

  // ** Dequeue Uop
  val uopNext = Wire(new RenameUop)
  val uop = Reg(new RenameUop)
  val uopValid = RegInit(false.B)

  val queue = Reg(Vec(IQ_SIZE, new RenameUop))

  val headIndex = RegInit(0.U(IQ_IDX_W + 1))

  // * Can only handle one long-latency Fu
  val wbReverved = RegInit(VecInit(Seq.fill(32)(false.B)))
  for (i <- 0 until 32 - 1) {
    wbReverved(i) := wbReverved(i + 1)
  }
  wbReverved(31) := false.B

  // ** Writeback
  val writebackReady = Wire(Vec(IQ_SIZE, Vec(2, Bool())))
  for (j <- 0 until IQ_SIZE) {
    writebackReady(j)(0) := false.B
    writebackReady(j)(1) := false.B
    for (i <- 0 until MACHINE_WIDTH) {
      val writebackValid = io.IN_writebackUop(i).valid
      val writebackUop = io.IN_writebackUop(i).bits
      when (writebackValid) {
        when (queue(j).prs1 === writebackUop.prd) {
          queue(j).src1Ready := true.B
          writebackReady(j)(0) := true.B
        }
        when (queue(j).prs2 === writebackUop.prd) {
          queue(j).src2Ready := true.B
          writebackReady(j)(1) := true.B
        }
      }
    }    
  }  

  val ldqLimitPtr = Wire(RingBufferPtr(LDQ_SIZE))
  val stqLimitPtr = Wire(RingBufferPtr(STQ_SIZE))
  ldqLimitPtr.flag := ~io.IN_ldqBasePtr.flag
  ldqLimitPtr.index := io.IN_ldqBasePtr.index
  stqLimitPtr.flag := ~io.IN_stqBasePtr.flag
  stqLimitPtr.index := io.IN_stqBasePtr.index


  val readyVec = (0 until IQ_SIZE).map(i => {
    (i.U < headIndex && (queue(i).src1Ready || writebackReady(i)(0)) && 
                        (queue(i).src2Ready || writebackReady(i)(1))) &&
    (!hasFU(FuType.LSU).B || queue(i).fuType =/= FuType.LSU || !LSUOp.isLoad(queue(i).opcode) || queue(i).ldqPtr.isBefore(ldqLimitPtr)) &&
    (!hasFU(FuType.LSU).B || queue(i).fuType =/= FuType.LSU || !LSUOp.isStore(queue(i).opcode) || queue(i).stqPtr.isBefore(stqLimitPtr)) &&
    (!hasFU(FuType.CSR).B || queue(i).fuType =/= FuType.CSR || queue(i).robPtr.index === io.IN_robTailPtr.index) && 
    ((queue(i).fuType =/= FuType.ALU && queue(i).fuType =/= FuType.BRU) || !wbReverved(0)) && 
    (queue(i).fuType =/= FuType.DIV || !io.IN_idivBusy)
  })
  
  val hasReady = readyVec.reduce(_ || _)
  val deqIndex = PriorityEncoder(readyVec)
 
  val updateValid = io.OUT_issueUop.fire || !uopValid

  val doEnq = io.IN_renameUop.fire
  val doDeq = updateValid && hasReady

  val enqStall = headIndex === IQ_SIZE.U
  io.IN_renameUop.ready := !enqStall

  when (io.IN_flush) {
    headIndex := 0.U
  }.otherwise {
    headIndex := headIndex + doEnq - doDeq  
  }

  // ** Update output Registers
  uopNext := queue(deqIndex)
  when (doDeq) {
    uop := uopNext
    when(uopNext.fuType === FuType.MUL) {
      wbReverved(IMUL_DELAY - 1) := true.B      
    }
    when(uopNext.fuType === FuType.DIV) {
      wbReverved(IDIV_DELAY - 1) := true.B      
    }
    for (i <- 0 until IQ_SIZE - 1) {
      when (i.U >= deqIndex) {
        queue(i) := queue(i + 1)
        queue(i).src1Ready := queue(i + 1).src1Ready || writebackReady(i + 1)(0)
        queue(i).src2Ready := queue(i + 1).src2Ready || writebackReady(i + 1)(1)
      }      
    }
  }
  when (doEnq) {
    val enqIndex = Mux(doDeq, headIndex - 1.U, headIndex)(IQ_IDX_W.get - 1, 0)
    dontTouch(enqIndex)
    val renameUop = io.IN_renameUop.bits
    queue(enqIndex) := renameUop
    // * fix srcReady
    for (i <- 0 until MACHINE_WIDTH) {
      val writebackValid = io.IN_writebackUop(i).valid
      val writebackUop = io.IN_writebackUop(i).bits
      when (writebackValid) {
        when (renameUop.prs1 === writebackUop.prd) {
          queue(enqIndex).src1Ready := true.B
        }
        when (renameUop.prs2 === writebackUop.prd) {
          queue(enqIndex).src2Ready := true.B
        }
      }
    }    
  }

  when (io.IN_flush) {
    uopValid := false.B
  }.elsewhen (updateValid) {
    uopValid := hasReady
  }

  // ** Output
  io.OUT_issueUop.valid := uopValid
  io.OUT_issueUop.bits := uop
}