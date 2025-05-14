import chisel3._
import chisel3.util._
import utils._
import os.size

class InstBufferIO extends CoreBundle {
  val IN_fetchInsts = Flipped(Vec(FETCH_WIDTH, Valid(new InstSignal)))
  val OUT_instBufferReady = Output(Bool())

  val OUT_insts = Vec(ISSUE_WIDTH, Valid(new InstSignal))
  val IN_decodeReady = Input(Bool())
  
  val IN_flush = Input(Bool())
}

class InstBuffer extends CoreModule {
  val io = IO(new InstBufferIO)

  // Calculate required buffer size (power of 2)
  val bufferSize = 16
  
  val instBuffer = Reg(Vec(bufferSize, new InstSignal))

  val headPtr = RegInit(RingBufferPtr(size = bufferSize, flag = 0.U, index = 0.U))
  val tailPtr = RegInit(RingBufferPtr(size = bufferSize, flag = 0.U, index = 0.U))
  
  val deqFireNum = Mux(io.IN_decodeReady, PopCount(io.OUT_insts.map(_.valid)), 0.U)
  val enqFireNum = Mux(io.OUT_instBufferReady, PopCount(io.IN_fetchInsts.map(_.valid)), 0.U)

  val ready = tailPtr.distanceTo(headPtr) <= bufferSize.U - FETCH_WIDTH.U

  val pushIndex = RegNext(VecInit((0 until FETCH_WIDTH).map(i => {
    val startLine = Mux(io.IN_flush, RingBufferPtr(size = bufferSize, flag = 0.U, index = 0.U), headPtr + enqFireNum)
    (startLine.index + i.U)
  })))

  val popIndex = RegNext(VecInit((0 until ISSUE_WIDTH).map(i => {
    val startLine = Mux(io.IN_flush, RingBufferPtr(size = bufferSize, flag = 0.U, index = 0.U), tailPtr + deqFireNum)
    (startLine.index + i.U)
  })))

  io.OUT_instBufferReady := ready

  (0 until FETCH_WIDTH).foreach { i =>
    when(io.IN_fetchInsts(i).valid && io.OUT_instBufferReady) {
      instBuffer(pushIndex(i)) := io.IN_fetchInsts(i).bits
    }
  }

  (0 until ISSUE_WIDTH).foreach { i =>
    io.OUT_insts(i).bits := instBuffer(popIndex(i))
    io.OUT_insts(i).valid := (tailPtr + i.U).isBefore(headPtr)
  }

  when(io.IN_flush) {
    headPtr := RingBufferPtr(size = bufferSize, flag = 0.U, index = 0.U)
    tailPtr := RingBufferPtr(size = bufferSize, flag = 0.U, index = 0.U)
  }.otherwise {
    headPtr := headPtr + enqFireNum
    tailPtr := tailPtr + deqFireNum
  }
}