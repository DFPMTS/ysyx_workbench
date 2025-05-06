import chisel3._
import chisel3.util._
import utils._

class SRAMTemplateR(N: Int, ways: Int, width: Int, writeWidth: Int) extends CoreBundle {
  val en = Bool()
  val addr = UInt(log2Up(N).W)
  val rdata = Flipped(Vec(ways, UInt(width.W)))
  def apply(paddr: UInt, lineBytes: Int, en: Bool) = {
    this.addr := paddr(log2Up(N) - 1 + log2Up(lineBytes), log2Up(lineBytes))
    this.en := en
  }
}

class SRAMTemplateRW(N: Int, ways: Int, width: Int, writeWidth: Int) extends SRAMTemplateR(N, ways, width, writeWidth) {
  val write = Bool()
  val wdata = UInt(width.W)
  val way = UInt(log2Up(ways).W)
  val wmask = UInt((width / writeWidth).W)
  def apply(paddr: UInt, lineBytes: Int, write: UInt, way: UInt, mask: UInt, data: UInt, en: Bool) = {
    this.addr := paddr(log2Up(N) - 1 + log2Up(lineBytes), log2Up(lineBytes))
    this.wmask := mask
    this.way := way
    this.write := write
    this.wdata := data
    this.en := en
  }
}

// * N: 数量 width: 元素宽度 writeWidth: wmask每一位对应的宽度
class SRAMTemplateIO(N: Int, ways: Int, width: Int, writeWidth: Int) extends CoreBundle {  
  val r = Flipped(new SRAMTemplateR(N, ways, width, writeWidth))
  val rw = Flipped(new SRAMTemplateRW(N, ways, width, writeWidth))
}


class SRAMTemplate(N: Int, ways: Int, width: Int, writeWidth: Int) extends CoreModule {
  val io = IO(new SRAMTemplateIO(N, ways, width, writeWidth))

  assert(width % writeWidth == 0, "width must be multiple of writeWidth")

  val numEntries = width / writeWidth
  val PhysicalSet = Vec(ways * numEntries, UInt(writeWidth.W))
  val Line = Vec(numEntries, UInt(writeWidth.W))
  val Set = Vec(ways, Line)
  val array = SyncReadMem(N, PhysicalSet, SyncReadMem.ReadFirst)

  val writeDataVec = Fill(ways, io.rw.wdata).asTypeOf(PhysicalSet) 
  val writeMaskVec = (io.rw.wmask << (io.rw.way * numEntries.U)).take(numEntries * ways)

  // * Port0: R 读通道
  io.r.rdata := array.read(io.r.addr, io.r.en).asTypeOf(io.r.rdata)

  // * Port1: RW 读写通道
  io.rw.rdata := array.readWrite(io.rw.addr, writeDataVec, writeMaskVec.asBools, io.rw.en, io.rw.write).asTypeOf(io.rw.rdata)
}

class XilinxBRAM(N: Int, ways: Int, width: Int, writeWidth: Int) extends CoreModule {
  val io = IO(new SRAMTemplateIO(N, ways, width, writeWidth))

  val NumCol = width / writeWidth
  val ColWidth = writeWidth
  val PhysicalSet = Vec(ways * NumCol, UInt(ColWidth.W))
  val Line = Vec(NumCol, UInt(ColWidth.W))
  val Set = Vec(ways, Line)
  val bramArray = Module(new BRAM1R1RW(N, ways * NumCol, ColWidth))

  val writeDataVec = Fill(ways, io.rw.wdata).asTypeOf(PhysicalSet) 
  // val writeMaskVec = (io.rw.wmask << (io.rw.way * numEntries.U)).take(numEntries * ways)
  val writeMaskVec = Wire(Vec(ways, UInt(NumCol.W)))
  for (i <- 0 until ways) {
    writeMaskVec(i) := Mux(io.rw.way === i.U, io.rw.wmask, 0.U)
  }

  bramArray.io.clk := clock

  bramArray.io.addra := io.r.addr
  bramArray.io.addrb := io.rw.addr

  bramArray.io.dinb := writeDataVec.asUInt

  bramArray.io.ena := io.r.en
  bramArray.io.enb := io.rw.en

  bramArray.io.web := writeMaskVec.asUInt
  
  io.r.rdata := bramArray.io.douta.asTypeOf(io.r.rdata)
  io.rw.rdata := bramArray.io.doutb.asTypeOf(io.rw.rdata)
}

class BRAM1R1RW(Depth: Int, NumCol: Int, ColWidth: Int) extends BlackBox(Map("DEPTH" -> Depth,  "NUM_COL" -> NumCol, "COL_WIDTH" -> ColWidth)) with 
HasBlackBoxInline {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val addra = Input(UInt(log2Ceil(Depth).W))
    val addrb = Input(UInt(log2Ceil(Depth).W))
    val dinb = Input(UInt((NumCol * ColWidth).W))
    val ena = Input(Bool())
    val enb = Input(Bool())
    val web = Input(UInt(NumCol.W))
    val douta = Output(UInt((NumCol * ColWidth).W))
    val doutb = Output(UInt((NumCol * ColWidth).W))
  })
  setInline("BRAM1R1RW.sv",
  """ 
  |module BRAM1R1RW #(
  |    parameter integer NUM_COL = 32,
  |    parameter integer DEPTH = 1024,
  |    parameter integer COL_WIDTH = 8
  |) (
  |  input  logic clk,
  |  input  logic [$clog2(DEPTH)-1:0] addra,
  |  input  logic [$clog2(DEPTH)-1:0] addrb,
  |  input  logic [(NUM_COL*COL_WIDTH)-1:0] dinb,
  |  input  logic ena,
  |  input  logic enb,
  |  input  logic [NUM_COL-1:0] web,
  |  output logic [(NUM_COL*COL_WIDTH)-1:0] douta,
  |  output logic [(NUM_COL*COL_WIDTH)-1:0] doutb
  |);
  |
  |reg [(NUM_COL*COL_WIDTH)-1:0] BRAM [DEPTH-1:0];
  |reg [(NUM_COL*COL_WIDTH)-1:0] ram_data_a = {(NUM_COL*COL_WIDTH){1'b0}};
  |reg [(NUM_COL*COL_WIDTH)-1:0] ram_data_b = {(NUM_COL*COL_WIDTH){1'b0}};
  |
  |// The following code either initializes the memory values to a specified file or to all zeros to match hardware
  |generate
  |    integer ram_index;
  |    initial
  |      for (ram_index = 0; ram_index < DEPTH; ram_index = ram_index + 1)
  |        BRAM[ram_index] = {(NUM_COL*COL_WIDTH){1'b0}};
  |endgenerate
  |always @(posedge clk)
  |  if (ena) begin
  |    ram_data_a <= BRAM[addra];
  |  end
  |always @(posedge clk)
  |  if (enb) begin
  |    ram_data_b <= BRAM[addrb];
  |  end
  |
  |generate
  |genvar i;
  |   for (i = 0; i < NUM_COL; i = i+1) begin: byte_write
  |     always @(posedge clk)
  |       if (enb)
  |         if (web[i])
  |           BRAM[addrb][(i+1)*COL_WIDTH-1:i*COL_WIDTH] <= dinb[(i+1)*COL_WIDTH-1:i*COL_WIDTH];
  |   end
  |endgenerate
  |
  |
  |assign douta = ram_data_a;
  |assign doutb = ram_data_b;
  |
  |endmodule
  """.stripMargin)
}