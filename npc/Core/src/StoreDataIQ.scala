import chisel3._
import chisel3.util._
import utils._

class StoreDataReadReq extends CoreBundle {
  val stqPtr = RingBufferPtr(STQ_SIZE)
  val prs = UInt(PREG_IDX_W)
  val ready = Vec(2, Bool())
}

class StoreData extends CoreBundle {
  val data = UInt(XLEN.W)
  val stqPtr = RingBufferPtr(STQ_SIZE)
}

class StoreDataIQIO extends CoreBundle {
  val IN_renameUop = Flipped(Decoupled(new RenameUop))
  val IN_writebackUop = Flipped(Vec(WRITEBACK_WIDTH, Valid(new WritebackUop)))
  val OUT_storeDataReadReq = Decoupled(new StoreDataReadReq)
  val IN_stqBasePtr = Flipped(RingBufferPtr(STQ_SIZE))
  val OUT_storeDataStqLimit = Valid(RingBufferPtr(STQ_SIZE))

  // * Snoop AGU -> StoreQueue
  val IN_AGUUop = Flipped(Valid(new AGUUop))

  val IN_flush = Flipped(Bool())
  val IN_flushStqPtr = Flipped(RingBufferPtr(STQ_SIZE))
}

class StoreDataIQ extends CoreModule {
  val io = IO(new StoreDataIQIO)

  val uopNext = Wire(new StoreDataReadReq)
  val uop = Reg(new StoreDataReadReq)
  val uopValid = RegInit(false.B)

  val queue = Reg(Vec(IQ_SIZE, new StoreDataReadReq))

  val headIndex = RegInit(0.U(IQ_IDX_W + 1))

  val writebackReady = Wire(Vec(IQ_SIZE, Vec(2, Bool())))
  for (j <- 0 until IQ_SIZE) {
    writebackReady(j)(0) := false.B
    writebackReady(j)(1) := false.B
    for (i <- 0 until WRITEBACK_WIDTH) {
      val writebackValid = io.IN_writebackUop(i).valid
      val writebackUop = io.IN_writebackUop(i).bits
      when (writebackValid) {
        when (queue(j).prs === writebackUop.prd) {
          queue(j).ready(1) := true.B
          writebackReady(j)(1) := true.B
        }
      }
    }
    val AGUStoreUopValid = io.IN_AGUUop.valid && 
                           (io.IN_AGUUop.bits.fuType === FuType.LSU && LSUOp.isStore(io.IN_AGUUop.bits.opcode))
    val AGUStoreUop = io.IN_AGUUop.bits
    when (AGUStoreUopValid) {
      when (queue(j).stqPtr === AGUStoreUop.stqPtr) {
        queue(j).ready(0) := true.B
        writebackReady(j)(0) := true.B
      }
    }
  }
  val stqLimitPtr = Wire(RingBufferPtr(STQ_SIZE))
  stqLimitPtr.flag := ~io.IN_stqBasePtr.flag
  stqLimitPtr.index := io.IN_stqBasePtr.index

  val storeDataStqLimitValid = RegInit(false.B)
  val storeDataStqPtr = Reg(RingBufferPtr(STQ_SIZE))

  storeDataStqLimitValid := headIndex =/= 0.U
  storeDataStqPtr := queue(0).stqPtr

  val readyVec = (0 until IQ_SIZE).map(i => {
    (i.U < headIndex && ((queue(i).stqPtr.isBefore(stqLimitPtr))) && 
                        (queue(i).ready.asUInt | writebackReady(i).asUInt).andR)})
  
  val hasReady = readyVec.reduce(_ || _)
  val deqIndex = PriorityEncoder(readyVec)
 
  val updateValid = (io.OUT_storeDataReadReq.fire || !uopValid) && !io.IN_flush

  val doEnq = io.IN_renameUop.fire && 
              (io.IN_renameUop.bits.fuType === FuType.LSU && LSUOp.isStore(io.IN_renameUop.bits.opcode))
  val doDeq = updateValid && hasReady

  val enqStall = headIndex === IQ_SIZE.U
  io.IN_renameUop.ready := !enqStall

  val flushVec = VecInit(queue.map(e => {
    !e.stqPtr.isBefore(io.IN_flushStqPtr) || !e.ready(0)
  }))
  val flushHeadIndex = PriorityEncoder(flushVec.appended(true.B))
  dontTouch(flushVec)

  when (io.IN_flush) {
    headIndex := Mux(flushHeadIndex < headIndex, flushHeadIndex, headIndex)
  }.otherwise {
    headIndex := headIndex + doEnq - doDeq  
  }

  // ** Update output Registers
  uopNext := queue(deqIndex)
  when (doDeq) {
    uop := uopNext
    for (i <- 0 until IQ_SIZE - 1) {
      when (i.U >= deqIndex) {
        queue(i) := queue(i + 1)
        for (k <- 0 until 2) {
          queue(i).ready(k) := queue(i + 1).ready(k) || writebackReady(i + 1)(k)
        }
      }      
    }
  }
  when (doEnq) {
    val enqIndex = Mux(doDeq, headIndex - 1.U, headIndex)(IQ_IDX_W.get - 1, 0)
    dontTouch(enqIndex)
    val renameUop = io.IN_renameUop.bits
    queue(enqIndex).prs := renameUop.prs2
    queue(enqIndex).stqPtr := renameUop.stqPtr
    queue(enqIndex).ready(0) := false.B
    queue(enqIndex).ready(1) := renameUop.src2Ready
    // * fix srcReady
    for (i <- 0 until WRITEBACK_WIDTH) {
      val writebackValid = io.IN_writebackUop(i).valid
      val writebackUop = io.IN_writebackUop(i).bits
      when (writebackValid) {
        when (renameUop.prs2 === writebackUop.prd) {
          queue(enqIndex).ready(1) := true.B
        }
      }
    }    
  }

  when (updateValid) {
    uopValid := hasReady
  }

  // ** Output

  io.OUT_storeDataStqLimit.valid := storeDataStqLimitValid
  io.OUT_storeDataStqLimit.bits := storeDataStqPtr

  io.OUT_storeDataReadReq.valid := uopValid
  io.OUT_storeDataReadReq.bits := uop
}
