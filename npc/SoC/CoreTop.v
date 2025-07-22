module core_top(
    input           aclk,
    input           aresetn,
    input    [ 7:0] intrpt, 
    //AXI interface 
    //read reqest
    output   [ 3:0] arid,
    output   [31:0] araddr,
    output   [ 7:0] arlen,
    output   [ 2:0] arsize,
    output   [ 1:0] arburst,
    output   [ 1:0] arlock,
    output   [ 3:0] arcache,
    output   [ 2:0] arprot,
    output          arvalid,
    input           arready,
    //read back
    input    [ 3:0] rid,
    input    [31:0] rdata,
    input    [ 1:0] rresp,
    input           rlast,
    input           rvalid,
    output          rready,
    //write request
    output   [ 3:0] awid,
    output   [31:0] awaddr,
    output   [ 7:0] awlen,
    output   [ 2:0] awsize,
    output   [ 1:0] awburst,
    output   [ 1:0] awlock,
    output   [ 3:0] awcache,
    output   [ 2:0] awprot,
    output          awvalid,
    input           awready,
    //write data
    output   [ 3:0] wid,
    output   [31:0] wdata,
    output   [ 3:0] wstrb,
    output          wlast,
    output          wvalid,
    input           wready,
    //write back
    input    [ 3:0] bid,
    input    [ 1:0] bresp,
    input           bvalid,
    output          bready,

    //debug
    input           break_point,//无需实现功能，仅提供接口即可，输入1’b0
    input           infor_flag,//无需实现功能，仅提供接口即可，输入1’b0
    input  [ 4:0]   reg_num,//无需实现功能，仅提供接口即可，输入5’b0
    output          ws_valid,//无需实现功能，仅提供接口即可
    output [31:0]   rf_rdata,//无需实现功能，仅提供接口即可

    //debug info
    output [31:0] debug0_wb_pc,
    output [ 3:0] debug0_wb_rf_wen,
    output [ 4:0] debug0_wb_rf_wnum,
    output [31:0] debug0_wb_rf_wdata
);

reg reset;
always @(posedge aclk) begin
  reset <= ~aresetn;
end

Core core_u(
  .clock(aclk),
  .reset(reset),
  .io_master_awready(awready),
  .io_master_awvalid(awvalid),
  .io_master_awaddr(awaddr),
  .io_master_awid(awid),
  .io_master_awlen(awlen),
  .io_master_awsize(awsize),
  .io_master_awburst(awburst),
  .io_master_wready(wready),
  .io_master_wvalid(wvalid),
  .io_master_wid(wid),
  .io_master_wdata(wdata),
  .io_master_wstrb(wstrb),
  .io_master_wlast(wlast),
  .io_master_bready(bready),
  .io_master_bvalid(bvalid),
  .io_master_bresp(bresp),
  .io_master_bid(bid),
  .io_master_arready(arready),
  .io_master_arvalid(arvalid),
  .io_master_araddr(araddr),
  .io_master_arid(arid),
  .io_master_arlen(arlen),
  .io_master_arsize(arsize),
  .io_master_arburst(arburst),
  .io_master_rready(rready),
  .io_master_rvalid(rvalid),
  .io_master_rresp(rresp),
  .io_master_rdata(rdata),
  .io_master_rlast(rlast),
  .io_master_rid(rid),
  .io_hwIntr(intrpt),
  .io_commitUop_bits_pc(debug0_wb_pc),
  .io_commitUop_bits_rd(debug0_wb_rf_wnum),
  .io_commitUop_valid(debug0_wb_rf_wen),
  .io_commitUop_bits_result(debug0_wb_rf_wdata)
);

assign arlock = 2'b00; // AXI lock signal, not used
assign arcache = 4'b0000; // AXI cache signal, not used
assign arprot = 3'b000; // AXI protection signal, not used

assign awlock = 2'b00; // AXI lock signal, not used
assign awcache = 4'b0000; // AXI cache signal, not used
assign awprot = 3'b000; // AXI protection signal, not used

assign ws_valid = 0;
assign rf_rdata = 0;

endmodule;