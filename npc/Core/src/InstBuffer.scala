import chisel3._
import chisel3.util._
import utils._

class InstBufferIO extends CoreBundle {
  val in = Flipped(Valid(new InstSignal))
  val fetchCanContinue = Bool()
  val out = Decoupled(new InstSignal)
  val flush = Input(Bool())
}

class InstBuffer extends CoreModule {
  val io = IO(new InstBufferIO)

  val INST_BUFFER_SIZE = 4
  val buffer = Reg(Vec(INST_BUFFER_SIZE, new InstSignal))
  val outReg = Reg(new InstSignal)
  val outValid = RegInit(false.B)
  val head = RegInit(RingBufferPtr(size = INST_BUFFER_SIZE, flag = 0.U, index = 0.U))
  val tail = RegInit(RingBufferPtr(size = INST_BUFFER_SIZE, flag = 0.U, index = 0.U))

  io.fetchCanContinue := head.distanceTo(tail) < 2.U
  val empty = head === tail

  // * flush
  when(io.flush) {
    head := 0.U.asTypeOf(head)
    tail := 0.U.asTypeOf(tail)
  }.otherwise {
    when(io.in.fire) {
      tail := tail + 1.U
    }
    when(io.out.fire) {
      head := head + 1.U
    }
  }

  // * enqueue
  when(io.in.fire) {
    buffer(tail.index) := io.in.bits
  }

  // * dequeue
  io.out.valid := !empty
  io.out.bits := buffer(head.index)
}
