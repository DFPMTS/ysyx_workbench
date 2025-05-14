import chisel3._
import chisel3.util._
import utils._
import os.write

class BTBUpdate extends CoreBundle {
  val pc = UInt(XLEN.W)
  val flush = Bool()
  val target = UInt(XLEN.W)
  val brType = BrType()

  def toBTBEntry() = {
    val entry = Wire(new BTBEntry)
    entry.valid := !flush
    entry.tag := pc(XLEN - 1, XLEN - BTB_TAG)
    entry.target := target
    entry.brType := brType
    entry
  }
}

class BTBEntry extends CoreBundle {
  val valid = Bool()
  val tag = UInt(BTB_TAG.W)
  val target = UInt(XLEN.W)
  val brType = BrType()
}

class BTBIO extends CoreBundle {
  val IN_btbUpdate = Flipped(Valid(new BTBUpdate))
  val IN_fixBTBUpdate = Flipped(Valid(new BTBUpdate))

  val IN_pc = Flipped(UInt(XLEN.W))
  val OUT_btbRead = Vec(FETCH_WIDTH, new BTBEntry)
}

class BTB extends CoreModule {
  val io = IO(new BTBIO)
  
  val btb = Seq.fill(FETCH_WIDTH)(Module(new XilinxBRAM(BTB_SIZE, (new BTBEntry).getWidth, (new BTBEntry).getWidth)))

  val resetIndex = RegInit(0.U(log2Up(BTB_SIZE).W))
  val resetDone = RegInit(false.B)

  when(resetIndex === (BTB_SIZE - 1).U) {
    resetDone := true.B
  }.otherwise {
    resetIndex := resetIndex + 1.U
  }

  for (i <- 0 until FETCH_WIDTH) {
    btb(i).io.r(io.IN_pc, (FETCH_WIDTH * 4), true.B)
    when(!resetDone) {
      io.OUT_btbRead(i) := 0.U.asTypeOf(new BTBEntry)
    }.otherwise {
      io.OUT_btbRead(i) := btb(i).io.r.rdata.asTypeOf(new BTBEntry)
    }
  }
  
  val bruUpdateBank = if (FETCH_WIDTH == 1) 0.U else io.IN_btbUpdate.bits.pc(log2Up(FETCH_WIDTH) - 1 + 2, 2)
  val bruUpdateEntry = io.IN_btbUpdate.bits.toBTBEntry()
  val fixUpdateBank = if (FETCH_WIDTH == 1) 0.U else io.IN_fixBTBUpdate.bits.pc(log2Up(FETCH_WIDTH) - 1 + 2, 2)
  val fixUpdateEntry = io.IN_fixBTBUpdate.bits.toBTBEntry()
  for (i <- 0 until FETCH_WIDTH) {
    val bruWriteEn = io.IN_btbUpdate.valid && bruUpdateBank === i.U
    val fixWriteEn = io.IN_fixBTBUpdate.valid && fixUpdateBank === i.U
    when(!resetDone){
      btb(i).io.rw(Cat(resetIndex, 0.U(log2Up(FETCH_WIDTH * 4).W)), (FETCH_WIDTH * 4), true.B, 0.U, 1.U, 0.U.asUInt, true.B)
    }.elsewhen(bruWriteEn) {
      btb(i).io.rw(io.IN_btbUpdate.bits.pc, (FETCH_WIDTH * 4), true.B, 0.U, 1.U, bruUpdateEntry.asUInt, bruWriteEn)    
    }.otherwise {
      btb(i).io.rw(io.IN_fixBTBUpdate.bits.pc, (FETCH_WIDTH * 4), true.B, 0.U, 1.U, fixUpdateEntry.asUInt, fixWriteEn)
    }
  }
}