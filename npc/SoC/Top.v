module Top (
  input wire SYS_CLK_0_clk_p,
  input wire SYS_CLK_0_clk_n,
  input wire sys_rst_0,

  input UART_0_rxd,
  output UART_0_txd,

  output [13:0]DDR3_0_addr,
  output [2:0]DDR3_0_ba,
  output DDR3_0_cas_n,
  output [0:0]DDR3_0_ck_n,
  output [0:0]DDR3_0_ck_p,
  output [0:0]DDR3_0_cke,
  output [0:0]DDR3_0_cs_n,
  output [3:0]DDR3_0_dm,

  inout [31:0]DDR3_0_dq,
  inout [3:0]DDR3_0_dqs_n,
  inout [3:0]DDR3_0_dqs_p,

  output [0:0]DDR3_0_odt,
  output DDR3_0_ras_n,
  output DDR3_0_reset_n,
  output DDR3_0_we_n
);

  wire [31:0] S00_AXI_0_araddr;
  wire [1:0]S00_AXI_0_arburst;
  wire [3:0]S00_AXI_0_arcache;
  wire [7:0]S00_AXI_0_arlen;
  wire [0:0]S00_AXI_0_arlock;
  wire [2:0]S00_AXI_0_arprot;
  wire [3:0]S00_AXI_0_arqos;
  wire S00_AXI_0_arready;
  wire [2:0]S00_AXI_0_arsize;
  wire S00_AXI_0_arvalid;
  wire [31:0]S00_AXI_0_awaddr;
  wire [1:0]S00_AXI_0_awburst;
  wire [3:0]S00_AXI_0_awcache;
  wire [7:0]S00_AXI_0_awlen;
  wire [0:0]S00_AXI_0_awlock;
  wire [2:0]S00_AXI_0_awprot;
  wire [3:0]S00_AXI_0_awqos;
  wire S00_AXI_0_awready;
  wire [2:0]S00_AXI_0_awsize;
  wire S00_AXI_0_awvalid;
  wire S00_AXI_0_bready;
  wire [1:0]S00_AXI_0_bresp;
  wire S00_AXI_0_bvalid;
  wire [31:0]S00_AXI_0_rdata;
  wire S00_AXI_0_rlast;
  wire S00_AXI_0_rready;
  wire [1:0]S00_AXI_0_rresp;
  wire S00_AXI_0_rvalid;
  wire [31:0]S00_AXI_0_wdata;
  wire S00_AXI_0_wlast;
  wire S00_AXI_0_wready;
  wire [3:0]S00_AXI_0_wstrb;
  wire S00_AXI_0_wvalid;


  wire clk_out1_0;
  wire init_calib_complete_0;
  wire mb_reset_0;

  wire core_clk = clk_out1_0;
  reg core_reset;

  always @(posedge core_clk) begin
    core_reset <= mb_reset_0 || !init_calib_complete_0;
  end

  Core core(
        .clock(core_clk),
        .reset(core_reset),
        .io_master_awready(S00_AXI_0_awready),
        .io_master_awvalid(S00_AXI_0_awvalid),
        .io_master_awaddr(S00_AXI_0_awaddr),
        .io_master_awid(S00_AXI_0_awid),
        .io_master_awlen(S00_AXI_0_awlen),
        .io_master_awsize(S00_AXI_0_awsize),
        .io_master_awburst(S00_AXI_0_awburst),
        .io_master_wready(S00_AXI_0_wready),
        .io_master_wvalid(S00_AXI_0_wvalid),
        .io_master_wdata(S00_AXI_0_wdata),
        .io_master_wstrb(S00_AXI_0_wstrb),
        .io_master_wlast(S00_AXI_0_wlast),
        .io_master_bready(S00_AXI_0_bready),
        .io_master_bvalid(S00_AXI_0_bvalid),
        .io_master_bresp(S00_AXI_0_bresp),
        .io_master_bid(S00_AXI_0_bid),
        .io_master_arready(S00_AXI_0_arready),
        .io_master_arvalid(S00_AXI_0_arvalid),
        .io_master_araddr(S00_AXI_0_araddr),
        .io_master_arid(S00_AXI_0_arid),
        .io_master_arlen(S00_AXI_0_arlen),
        .io_master_arsize(S00_AXI_0_arsize),
        .io_master_arburst(S00_AXI_0_arburst),
        .io_master_rready(S00_AXI_0_rready),
        .io_master_rvalid(S00_AXI_0_rvalid),
        .io_master_rresp(S00_AXI_0_rresp),
        .io_master_rdata(S00_AXI_0_rdata),
        .io_master_rlast(S00_AXI_0_rlast),
        .io_master_rid(S00_AXI_0_rid),
        .io_interrupt(1'b0)
  );


  SoC SoC_i
       (.DDR3_0_addr(DDR3_0_addr),
        .DDR3_0_ba(DDR3_0_ba),
        .DDR3_0_cas_n(DDR3_0_cas_n),
        .DDR3_0_ck_n(DDR3_0_ck_n),
        .DDR3_0_ck_p(DDR3_0_ck_p),
        .DDR3_0_cke(DDR3_0_cke),
        .DDR3_0_cs_n(DDR3_0_cs_n),
        .DDR3_0_dm(DDR3_0_dm),
        .DDR3_0_dq(DDR3_0_dq),
        .DDR3_0_dqs_n(DDR3_0_dqs_n),
        .DDR3_0_dqs_p(DDR3_0_dqs_p),
        .DDR3_0_odt(DDR3_0_odt),
        .DDR3_0_ras_n(DDR3_0_ras_n),
        .DDR3_0_reset_n(DDR3_0_reset_n),
        .DDR3_0_we_n(DDR3_0_we_n),
        .S00_AXI_0_araddr(S00_AXI_0_araddr),
        .S00_AXI_0_arburst(S00_AXI_0_arburst),
        .S00_AXI_0_arcache(S00_AXI_0_arcache),
        .S00_AXI_0_arlen(S00_AXI_0_arlen),
        .S00_AXI_0_arlock(S00_AXI_0_arlock),
        .S00_AXI_0_arprot(S00_AXI_0_arprot),
        .S00_AXI_0_arqos(S00_AXI_0_arqos),
        .S00_AXI_0_arready(S00_AXI_0_arready),
        .S00_AXI_0_arsize(S00_AXI_0_arsize),
        .S00_AXI_0_arvalid(S00_AXI_0_arvalid),
        .S00_AXI_0_awaddr(S00_AXI_0_awaddr),
        .S00_AXI_0_awburst(S00_AXI_0_awburst),
        .S00_AXI_0_awcache(S00_AXI_0_awcache),
        .S00_AXI_0_awlen(S00_AXI_0_awlen),
        .S00_AXI_0_awlock(S00_AXI_0_awlock),
        .S00_AXI_0_awprot(S00_AXI_0_awprot),
        .S00_AXI_0_awqos(S00_AXI_0_awqos),
        .S00_AXI_0_awready(S00_AXI_0_awready),
        .S00_AXI_0_awsize(S00_AXI_0_awsize),
        .S00_AXI_0_awvalid(S00_AXI_0_awvalid),
        .S00_AXI_0_bready(S00_AXI_0_bready),
        .S00_AXI_0_bresp(S00_AXI_0_bresp),
        .S00_AXI_0_bvalid(S00_AXI_0_bvalid),
        .S00_AXI_0_rdata(S00_AXI_0_rdata),
        .S00_AXI_0_rlast(S00_AXI_0_rlast),
        .S00_AXI_0_rready(S00_AXI_0_rready),
        .S00_AXI_0_rresp(S00_AXI_0_rresp),
        .S00_AXI_0_rvalid(S00_AXI_0_rvalid),
        .S00_AXI_0_wdata(S00_AXI_0_wdata),
        .S00_AXI_0_wlast(S00_AXI_0_wlast),
        .S00_AXI_0_wready(S00_AXI_0_wready),
        .S00_AXI_0_wstrb(S00_AXI_0_wstrb),
        .S00_AXI_0_wvalid(S00_AXI_0_wvalid),
        .SYS_CLK_0_clk_n(SYS_CLK_0_clk_n),
        .SYS_CLK_0_clk_p(SYS_CLK_0_clk_p),
        .UART_0_rxd(UART_0_rxd),
        .UART_0_txd(UART_0_txd),
        .clk_out1_0(clk_out1_0),
        .init_calib_complete_0(init_calib_complete_0),
        .mb_reset_0(mb_reset_0),
        .sys_rst_0(sys_rst_0));

endmodule