import chisel3._
import chisel3.util._
import utils._

class AmoUnitIO extends CoreBundle {
  val IN_AGUUop = Flipped(Valid(new AGUUop))
  val IN_amoAck = Flipped(Valid(new StoreAck))

  val IN_storeQueueEmpty = Flipped(Bool())
  val IN_storeBufferEmpty = Flipped(Bool())
  val IN_lsuBusy = Flipped(Bool())
  val OUT_amoActive = Bool()

  val OUT_amoUop = Decoupled(new AGUUop)
}

class AmoUnit extends CoreModule {
  val io = IO(new AmoUnitIO)
  
  val amoUop = Reg(new AGUUop)
  val amoUopValid = RegInit(false.B)
  val amoUopIssued = Reg(Bool())

  io.OUT_amoActive := amoUopValid

  when(io.IN_AGUUop.valid && io.IN_AGUUop.bits.fuType === FuType.AMO) {
    amoUop := io.IN_AGUUop.bits
    amoUopValid := true.B
    amoUopIssued := false.B
  }.otherwise {
    when(io.OUT_amoUop.fire) {
      amoUopIssued := true.B
    }
    when(io.IN_amoAck.valid) {
      when(io.IN_amoAck.bits.resp === 0.U) {
        amoUopValid := false.B
      }
      when(io.IN_amoAck.bits.resp === 1.U) {        
        amoUopIssued := false.B
      }
    }
  }
  io.OUT_amoUop.bits := amoUop
  io.OUT_amoUop.valid := amoUopValid && !io.IN_lsuBusy && RegNext(io.IN_storeBufferEmpty && io.IN_storeQueueEmpty) && !amoUopIssued
}
