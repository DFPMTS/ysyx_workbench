package utils

import chisel3._
import chisel3.util._

// 基本上是一个把AXI4的(AR/AW/W, R/B)打包成(Req, Resp)的简化总线，用于核内的通信


// AXI4Lite + id，用于Core<->MMU, Core<->Cache
class CoreBusLiteReq extends CoreBundle {
  val addr = UInt(XLEN.W)
  val data = UInt(XLEN.W)
  val rw = UInt(1.W) // read or write request
  val mask = UInt((XLEN / 8).W)
  val id = UInt(IDLEN.W)
}

class CoreBusLiteResp extends CoreBundle {
  val corrupt = Bool() // is the data invalid
  val rw = UInt(1.W) // read or write response
  val data = UInt(XLEN.W)
  val id = UInt(IDLEN.W)
}

// AXI4，用于MMU, Cache<->XBar
class CoreBusReq extends CoreBusLiteReq {
  val burst = UInt(2.W)
  val len = UInt(8.W)
}

class CoreBusResp extends CoreBusLiteResp {  
  val last = Bool()
}

class CoreBusLite extends CoreBundle {
  val req = Decoupled(new CoreBusLiteReq)
  val resp = Flipped(Decoupled(new CoreBusLiteResp))
}

class CoreBus extends CoreBundle {
  val req = Decoupled(new CoreBusReq)
  val resp = Flipped(Decoupled(new CoreBusResp))
}