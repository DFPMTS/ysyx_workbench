import chisel3._
import chisel3.util._
import utils._
import scala.util.Random

// ar.valid and aw.valid MUST NOT be high at the same time
class SRAM extends CoreModule {
  val io = IO(Flipped(new AXI4(AXI_DATA_WIDTH, AXI_ADDR_WIDTH)))

  // val read_lat  = random.LFSR(3, true.B, Some(3))
  // val write_lat = random.LFSR(2, true.B, Some(1))
  val read_lat  = 0.U
  val write_lat = 0.U
  val counter   = RegInit(0.U(10.W))

  val out_data_buffer = Reg(UInt(AXI_DATA_WIDTH.W))
  val arAddr = Reg(UInt(AXI_ADDR_WIDTH.W))
  val arId = Reg(UInt(4.W))
  val arLen = Reg(UInt(8.W))
  val arSize = Reg(UInt(3.W))
  val arCnt = RegInit(0.U(8.W))
  val readReady = RegInit(false.B)

  val awAddr = Reg(UInt(AXI_ADDR_WIDTH.W))
  val awId = Reg(UInt(4.W))
  val awLen = Reg(UInt(8.W))
  val awSize = Reg(UInt(3.W))

  val wData = Reg(UInt(AXI_DATA_WIDTH.W))
  val wStrb = Reg(UInt((AXI_DATA_WIDTH / 8).W))
  val writeReady = RegInit(false.B)
  val wCnt = RegInit(0.U(8.W))  // Track write data count

  val s_Idle :: s_Read :: s_ReadData :: s_WriteAddr :: s_WriteData :: s_WriteResp :: Nil = Enum(6)

  val next_state = WireDefault(s_Idle)
  val state      = RegNext(next_state, s_Idle)

  next_state := state
  counter := Mux(counter === 0.U, 0.U, counter - 1.U)
  switch(state) {
    is(s_Idle) {
      when(io.ar.fire) {
        next_state := s_Read
        arId := io.ar.bits.id
        arLen := io.ar.bits.len
        arCnt := 0.U
        arAddr := io.ar.bits.addr
        arSize := io.ar.bits.size
      }.elsewhen(io.aw.valid) {
        next_state := s_WriteAddr        
      }
    }    
    is(s_Read) {
      // Request memory read and transition to wait for data
      next_state := s_ReadData
      counter := read_lat
    }
    is(s_ReadData) {
      when(io.r.fire) {
        when(arCnt === arLen) {
          next_state := s_Idle
        }.otherwise {
          arCnt := arCnt + 1.U
          arAddr := arAddr + (1.U << arSize)
          next_state := s_Read  // Go back to request next data
        }
      }
    }
    is(s_WriteAddr) {
      counter := write_lat
      when(io.aw.fire) {
        next_state := s_WriteData
        awId := io.aw.bits.id
        awLen := io.aw.bits.len
        wCnt := 0.U
        awAddr := io.aw.bits.addr
        awSize := io.aw.bits.size
      }
    }
    is(s_WriteData) {
      when(io.w.fire) {
        when(wCnt === awLen) {
          next_state := s_WriteResp
        }.otherwise {
          wCnt := wCnt + 1.U
          awAddr := awAddr + (1.U << awSize)
        }
      }
    }
    is(s_WriteResp) {
      when(io.b.fire) {
        next_state := s_Idle
      }
    }
  }

  val perform_read = state === s_Read

  // Ready/Valid signals for AXI handshaking
  io.ar.ready := state === s_Idle
  io.aw.ready := state === s_WriteAddr
  io.w.ready  := state === s_WriteData
  io.r.valid  := state === s_ReadData && counter === 0.U
  io.b.valid  := state === s_WriteResp && counter === 0.U

  // Memory read interface
  val mem_read     = Module(new MemRead)
  mem_read.io.clk  := clock
  mem_read.io.addr := arAddr
  mem_read.io.en   := perform_read
  
  // Register the read data with one cycle latency
  val rdataAvail = RegNext(perform_read) 
  out_data_buffer := Mux(rdataAvail, mem_read.io.data_r, out_data_buffer)
  val rdata = Mux(rdataAvail, mem_read.io.data_r, out_data_buffer)

  // Memory write interface
  val mem_write     = Module(new MemWrite)
  mem_write.io.clk   := clock
  mem_write.io.addr  := awAddr
  mem_write.io.wdata := io.w.bits.data
  mem_write.io.wmask := io.w.bits.strb
  mem_write.io.en    := io.w.fire // Write when handshaking

  // Read response channel
  io.r.bits.resp := 0.U
  io.r.bits.data := rdata
  io.r.bits.last := arCnt === arLen
  io.r.bits.id   := arId

  // Write response channel
  io.b.bits.resp := 0.U
  io.b.bits.id   := awId
}