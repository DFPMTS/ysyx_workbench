import chisel3._
import chisel3.util._
import utils._

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

class AXI_Arbiter extends CoreModule {
  val io = IO(new Bundle {
    val IFUMaster = Flipped(new AXI4(AXI_DATA_WIDTH, AXI_ADDR_WIDTH))
    val LSUMaster = Flipped(new AXI4(AXI_DATA_WIDTH, AXI_ADDR_WIDTH))
    val winMaster = new AXI4(AXI_DATA_WIDTH, AXI_ADDR_WIDTH)
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

  val win = Wire(Flipped(new AXI4(AXI_DATA_WIDTH, AXI_ADDR_WIDTH)))

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

  // toWin <> toIn
  toIn :>= win
  // dontTouch(readCLINT)
  // dontTouch(readCLINTReg)
  // dontTouch(win)
  // dontTouch(toIn)
  // io.winMaster :>= toIn
  io.winMaster :<= toOut
}
