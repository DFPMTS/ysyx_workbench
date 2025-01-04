import chisel3._
import chisel3.util._
import os.stat
import os.truncate

// class AXI_Arbiter extends Module {
//   val io = IO(new Bundle {
//     val IFUMaster = Flipped(new AXI4(32, 32))
//     val LSUMaster = Flipped(new AXI4(32, 32))
//     val winMaster = new AXI4(32, 32)
//   })

//   val sIdle :: sIFU :: sLSU :: Nil = Enum(3)

//   val nextState = WireDefault(sIdle)
//   val state     = RegNext(nextState, sIdle)

//   val IFUreq = io.IFUMaster.ar.valid || io.IFUMaster.aw.valid || io.IFUMaster.w.valid
//   val LSUreq = io.LSUMaster.ar.valid || io.LSUMaster.aw.valid || io.LSUMaster.w.valid

//   val IFUreply = (io.IFUMaster.r.fire && io.IFUMaster.r.bits.last) || io.IFUMaster.b.fire
//   val LSUreply = (io.LSUMaster.r.fire && io.LSUMaster.r.bits.last) || io.LSUMaster.b.fire
//   // 注意这里有问题：假设一个master能发出多个请求（如ar通道多次握手），当第一个请求得到回复后就会直接变为Idle状态，这是错误的
//   // 修改方法1：让ar aw w仅能握手一次，即在ar aw w握手后将其ready置为false
//   nextState := MuxLookup(state, sIdle)(
//     Seq(
//       sIdle -> Mux(LSUreq, sLSU, Mux(IFUreq, sIFU, sIdle)),
//       sIFU -> Mux(IFUreply, sIdle, sIFU),
//       sLSU -> Mux(LSUreply, sIdle, sLSU)
//     )
//   )

//   for (master <- List(io.IFUMaster, io.LSUMaster)) {
//     master.ar.ready := false.B

//     master.aw.ready := false.B

//     master.w.ready := false.B

//     master.r.valid     := false.B
//     master.r.bits.data := 0.U
//     master.r.bits.resp := 0.U
//     master.r.bits.last := true.B
//     master.r.bits.id   := 0.U

//     master.b.valid     := false.B
//     master.b.bits.resp := 0.U
//     master.b.bits.id   := 0.U
//   }

//   for (slave <- List(io.winMaster)) {
//     slave.aw.valid      := false.B
//     slave.aw.bits.addr  := 0.U
//     slave.aw.bits.id    := 0.U
//     slave.aw.bits.len   := 0.U
//     slave.aw.bits.size  := 0.U
//     slave.aw.bits.burst := "b01".U

//     slave.w.valid     := false.B
//     slave.w.bits.data := 0.U
//     slave.w.bits.strb := 0.U
//     slave.w.bits.last := true.B

//     slave.ar.valid      := false.B
//     slave.ar.bits.addr  := 0.U
//     slave.ar.bits.id    := 0.U
//     slave.ar.bits.len   := 0.U
//     slave.ar.bits.size  := 0.U
//     slave.ar.bits.burst := "b01".U

//     slave.r.ready := false.B
//     slave.b.ready := false.B
//   }
//   // val win = WireDefault(io.LSUMaster)
//   switch(state) {
//     is(sIFU) {
//       // io.IFUMaster <> win
//       io.winMaster <> io.IFUMaster
//     }
//     is(sLSU) {
//       // io.LSUMaster <> win
//       io.winMaster <> io.LSUMaster
//     }
//   }
// }

class AXI_Arbiter extends Module {
  val io = IO(new Bundle {
    val IFUMaster = Flipped(new AXI4(32, 32))
    val LSUMaster = Flipped(new AXI4(32, 32))
    val winMaster = new AXI4(32, 32)
  })

  val sIdle :: sIFU :: sLSU :: Nil = Enum(3)

  val nextState = WireDefault(sIdle)
  val state     = RegNext(nextState, sIdle)

  val IFUreq = io.IFUMaster.ar.valid
  val LSUreq = io.LSUMaster.ar.valid

  val IFUreply = io.IFUMaster.r.fire && io.IFUMaster.r.bits.last
  val LSUreply = io.LSUMaster.r.fire && io.LSUMaster.r.bits.last
  // 注意这里有问题：假设一个master能发出多个请求（如ar通道多次握手），当第一个请求得到回复后就会直接变为Idle状态，这是错误的
  // 修改方法1：让ar aw w仅能握手一次，即在ar aw w握手后将其ready置为false
  nextState := MuxLookup(state, sIdle)(
    Seq(
      sIdle -> Mux(LSUreq, sLSU, Mux(IFUreq, sIFU, sIdle)),
      sIFU -> Mux(IFUreply, sIdle, sIFU),
      sLSU -> Mux(LSUreply, sIdle, sLSU)
    )
  )

  val win = Wire(Flipped(new AXI4(32, 32)))

  win :>= io.LSUMaster
  win :>= io.IFUMaster

  for (master <- List(io.IFUMaster, io.LSUMaster)) {
    master.ar.ready := false.B
    master.aw.ready := false.B
    master.w.ready  := false.B
    master.r.valid  := false.B
    master.b.valid  := false.B
  }

  io.LSUMaster.aw <> win.aw
  io.LSUMaster.w <> win.w
  io.LSUMaster.b <> win.b

  when((state === sIdle && !LSUreq && IFUreq) || state === sIFU) {
    win.ar <> io.IFUMaster.ar
    win.r <> io.IFUMaster.r
  }.otherwise {
    win.ar <> io.LSUMaster.ar
    win.r <> io.LSUMaster.r
  }

  // io.winMaster <> win
  // toIn :>= win
  // val toWin = WireDefault(win)

  val toOut = WireDefault(win)
  val toIn  = WireDefault(io.winMaster)

  // MMIO read 

  val mtime = RegInit(0.U(64.W))
  val mtimecmp = RegInit(0.U(64.W))
  val msip =RegInit(0.U(1.W))
  mtime := mtime + 1.U

  val CLINT_BASE = 0x11000000L.U(32.W)
  val MTIME_OFFSET = 0xbff8.U
  val MTIMECMP_OFFSET = 0x4000.U
  val MSIP_OFFSET = 0x0.U

  val mtimel = mtime(31, 0)
  val mtimeh = mtime(63, 32)
  val mtimecmpl = mtimecmp(31, 0)
  val mtimecmph = mtimecmp(63, 32)

    

  val readMMIO = win.ar.bits.addr(31,24) === 0x11.U
  val readMMIOReg = RegInit(false.B)
  val readMMIOData = WireInit(0.U(32.W))
  readMMIOReg := Mux(
    win.ar.valid && readMMIO,
    true.B,
    Mux(win.r.ready, false.B, readMMIOReg)
  )
  val raddr = win.ar.bits.addr
  when(raddr(31, 2) === (CLINT_BASE + MTIME_OFFSET)(31, 2)) {
    readMMIOData := mtimel
  }.elsewhen(raddr(31, 2) === (CLINT_BASE + MTIME_OFFSET + 4.U)(31, 2)) {
    readMMIOData := mtimeh
  }.elsewhen(raddr(31, 2) === (CLINT_BASE + MTIMECMP_OFFSET)(31, 2)) {
    readMMIOData := mtimecmpl
  }.elsewhen(raddr(31, 2) === (CLINT_BASE + MTIMECMP_OFFSET + 4.U)(31, 2)) {
    readMMIOData := mtimecmph
  }.elsewhen(raddr(31, 2) === (CLINT_BASE + MSIP_OFFSET)(31, 2)) {
    readMMIOData := msip
  }

  
  toOut.ar.valid   := win.ar.valid && !readMMIO
  toIn.ar.ready    := Mux(readMMIO, true.B, io.winMaster.ar.ready)
  toIn.r.valid     := Mux(readMMIOReg, true.B, io.winMaster.r.valid)
  toIn.r.bits.last := Mux(readMMIOReg, true.B, io.winMaster.r.bits.last)
  toIn.r.bits.data := Mux(readMMIOReg, readMMIOData, io.winMaster.r.bits.data)

  val writeMMIO = win.aw.valid && win.w.valid && win.aw.bits.addr(31,24) === 0x11.U
  val writeMMIOReg = RegInit(false.B)

  writeMMIOReg := Mux(
    win.aw.valid && win.w.valid && writeMMIO,
    true.B,
    Mux(win.b.fire, false.B, writeMMIOReg)
  )

  toOut.aw.valid := win.aw.valid && !writeMMIO
  toOut.w.valid  := win.w.valid && !writeMMIO
  toIn.aw.ready  := Mux(writeMMIO, true.B, io.winMaster.aw.ready)
  toIn.w.ready   := Mux(writeMMIO, true.B, io.winMaster.w.ready)
  toIn.b.valid   := Mux(writeMMIOReg, true.B, io.winMaster.b.valid)

  val waddr = win.aw.bits.addr
  when(waddr(31, 2) === (CLINT_BASE + MTIMECMP_OFFSET)(31, 2)) {
    mtimecmp := Cat(mtimecmp(63, 32), win.w.bits.data)
  }.elsewhen(waddr(31, 2) === (CLINT_BASE + MTIMECMP_OFFSET + 4.U)(31, 2)) {
    mtimecmp := Cat(win.w.bits.data, mtimecmp(31, 0))
  }.elsewhen(waddr(31, 2) === (CLINT_BASE + MSIP_OFFSET)(31, 2)) {
    msip := win.w.bits.data(0)
  }

  // toWin <> toIn
  toIn :>= win
  // dontTouch(readCLINT)
  // dontTouch(readCLINTReg)
  // dontTouch(win)
  // dontTouch(toIn)
  // io.winMaster :>= toIn
  io.winMaster :<= toOut
}
