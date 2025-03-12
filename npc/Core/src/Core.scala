import chisel3._
import chisel3.util._
import utils._
import chisel3.experimental.dataview._

class Core extends CoreModule {
  val io = IO(new Bundle {
    val master    = new AXI4ysyxSoC(32, 32)
    val slave     = Flipped(new AXI4ysyxSoC(32, 32))
    val interrupt = Input(Bool())
  })

  // * Internal MMIO 
  val internalMMIO = Module(new InternalMMIO)

  // * Cache
  val dcache = Module(new DCache)
  val cacheController = Module(new CacheController)

  val ifu = Module(new IFU)
  val itlb = Module(new TLB(size = 2, id = 0))
  val idu = Module(new IDU)
  val rename = Module(new Rename)
  val rob = Module(new ROB)
  val scheduler = Module(new Scheduler)
  val iq = Seq(
    Module(new IssueQueue(Seq(FuType.ALU, FuType.MUL, FuType.CSR))),
    Module(new IssueQueue(Seq(FuType.ALU, FuType.DIV))),
    Module(new IssueQueue(Seq(FuType.LSU))),
    Module(new IssueQueue(Seq(FuType.ALU))),
  )
  val dispatcher = Seq(
    Module(new Dispatcher(
      Seq(Seq(FuType.ALU, FuType.BRU), Seq(FuType.MUL), Seq(FuType.CSR))
    )),
    Module(new Dispatcher(
      Seq(Seq(FuType.ALU, FuType.BRU), Seq(FuType.DIV))
    )),
  )

  val readReg = Module(new ReadReg)
  val pReg = Module(new PReg)
  // * Port 0
  val alu0 = Module(new ALU)
  val mul  = Module(new MUL)
  val csr  = Module(new CSR)
  // * Port 1
  val alu1 = Module(new ALU)
  val div  = Module(new DIV)
  // * Port 2
  val agu  = Module(new AGU)
  val loadQueue = Module(new LoadQueue)
  val storeQueue = Module(new StoreQueue)
  val storeBuffer = Module(new StoreBuffer)
  val lsu  = Module(new NewLSU)
  val dtlb = Module(new TLB(size = 2, id = 1))
  val ptw  = Module(new PTW)
  val loadArb = Module(new LoadArbiter)


  val arbiter = Module(new AXI_Arbiter)

  val xtvalRecoder = Module(new XtvalRecoder)
  val flagHandler = Module(new FlagHandler)
  val flush = Wire(Bool())
  val redirect = Wire(new RedirectSignal)
  dontTouch(redirect)
  val TLBFlush = Wire(Bool())

  // * rename
  val renameUop = Wire(Vec(ISSUE_WIDTH, new RenameUop))
  val renameRobValid = Wire(Vec(ISSUE_WIDTH, Bool()))
  val renameRobReady = Wire(Bool())
  val renameIQValid = Wire(Vec(ISSUE_WIDTH, Bool()))
  dontTouch(renameUop)  
  dontTouch(renameRobValid)
  dontTouch(renameRobReady)
  dontTouch(renameIQValid)
  
  // * read register
  val readRegUop = Wire(Vec(MACHINE_WIDTH, Decoupled(new ReadRegUop)))
  dontTouch(readRegUop)

  // * writeback
  val writebackUop = Wire(Vec(MACHINE_WIDTH, Valid(new WritebackUop)))
  for (i <- 0 until MACHINE_WIDTH) {
    writebackUop(i).valid := false.B
    writebackUop(i).bits := 0.U.asTypeOf(new WritebackUop)
  }
  dontTouch(writebackUop)

  // * commit
  val commitUop = Wire(Vec(COMMIT_WIDTH, Valid(new CommitUop)))
  dontTouch(commitUop)  

  // * flag
  val flagUop = Wire(Valid(new FlagUop))
  dontTouch(flagUop)

  // * CSR ctrl
  val CSRCtrl = Wire(new CSRCtrl)
  dontTouch(CSRCtrl)

  // * IF
  ifu.io.redirect := redirect
  arbiter.io.winMaster.viewAs[AXI4ysyxSoC] <> io.master
  ifu.io.flushICache := false.B
  ifu.io.OUT_TLBReq <> itlb.io.IN_TLBReq
  ifu.io.IN_TLBResp <> itlb.io.OUT_TLBResp
  ifu.io.OUT_PTWReq <> ptw.io.IN_PTWReq(0)
  ifu.io.IN_PTWResp <> ptw.io.OUT_PTWResp
  ifu.io.IN_VMCSR <> csr.io.OUT_VMCSR
  ifu.io.IN_trapCSR <> csr.io.OUT_trapCSR
  itlb.io.IN_PTWResp <> ptw.io.OUT_PTWResp
  itlb.io.IN_TLBFlush := TLBFlush

  // * DE
  idu.io.IN_inst <> ifu.io.out
  idu.io.IN_flush := flush

  // * Rename
  rename.io.IN_decodeUop(0) <> idu.io.OUT_decodeUop
  rename.io.IN_commitUop <> commitUop
  rename.io.IN_writebackUop <> writebackUop
  rename.io.IN_issueQueueReady := scheduler.io.OUT_issueQueueReady
  rename.io.IN_robReady := renameRobReady
  rename.io.IN_flush := flush
  rename.io.IN_robTailPtr := rob.io.OUT_robTailPtr

  rename.io.IN_ldqTailPtr := rob.io.OUT_ldqTailPtr
  rename.io.IN_stqTailPtr := rob.io.OUT_stqTailPtr

  rename.io.OUT_renameUop <> renameUop
  rename.io.OUT_robValid <> renameRobValid
  rename.io.OUT_issueQueueValid <> renameIQValid

  // * ROB
  rob.io.IN_renameRobHeadPtr := rename.io.OUT_robHeadPtr
  for (i <- 0 until ISSUE_WIDTH) {
    rob.io.IN_renameUop(i).valid := renameRobValid(i)
    rob.io.IN_renameUop(i).bits := renameUop(i)
  }  
  rob.io.IN_writebackUop <> writebackUop
  rob.io.IN_flush := flush

  rob.io.OUT_renameUopReady <> renameRobReady
  rob.io.OUT_flagUop <> flagUop
  rob.io.OUT_commitUop <> commitUop
  rob.io.IN_stqBasePtr := storeQueue.io.OUT_stqBasePtr

  // * flag handler
  flagHandler.io.OUT_CSRCtrl <> CSRCtrl
  flagHandler.io.IN_trapCSR <> csr.io.OUT_trapCSR
  flagHandler.io.IN_flagUop <> flagUop
  flush := flagHandler.io.OUT_flush
  redirect := flagHandler.io.OUT_redirect
  TLBFlush := flagHandler.io.OUT_TLBFlush

  // * Scheduler
  scheduler.io.IN_issueQueueValid := renameIQValid
  scheduler.io.IN_renameUop := renameUop

  // * Issue Queue
  for (i <- 0 until MACHINE_WIDTH) {
    iq(i).io.IN_renameUop <> scheduler.io.OUT_renameUop(i)
    iq(i).io.IN_writebackUop := writebackUop
    iq(i).io.IN_robTailPtr := rob.io.OUT_robTailPtr
    iq(i).io.IN_ldqBasePtr := rob.io.OUT_ldqTailPtr
    iq(i).io.IN_stqBasePtr := storeQueue.io.OUT_stqBasePtr
    iq(i).io.IN_flush := flush
    iq(i).io.IN_idivBusy := div.io.OUT_idivBusy
  }

  // * Read Register
  readReg.io.IN_issueUop <> iq.map(_.io.OUT_issueUop)
  readReg.io.IN_readRegVal := pReg.io.OUT_pRegVal
  for (i <- 0 until MACHINE_WIDTH) {    
    readRegUop(i) <> readReg.io.OUT_readRegUop(i)
    readRegUop(i).ready := false.B
  }
  readReg.io.IN_flush := flush

  // * PReg
  pReg.io.IN_pRegIndex := readReg.io.OUT_readRegIndex
  pReg.io.IN_writebackUop := writebackUop

  // * Execute
  // ** Port 0: ALU / MUL / CSR
  dispatcher(0).io.IN_uop <> readRegUop(0)

  alu0.io.IN_flush := flush
  mul.io.IN_flush := flush

  alu0.io.IN_readRegUop <> dispatcher(0).io.OUT_uop(0)
  mul.io.IN_readRegUop  <> dispatcher(0).io.OUT_uop(1)
  csr.io.IN_readRegUop  <> dispatcher(0).io.OUT_uop(2)
  csr.io.IN_mtime := internalMMIO.io.OUT_mtime
  csr.io.IN_MTIP := internalMMIO.io.OUT_MTIP
  csr.io.IN_xtvalRec <> xtvalRecoder.io.OUT_tval
  csr.io.IN_CSRCtrl <> CSRCtrl

  val port0wbsel = Module(new WritebackSel(3))
  port0wbsel.io.IN_uop(0) := alu0.io.OUT_writebackUop
  port0wbsel.io.IN_uop(1) := mul.io.OUT_writebackUop
  port0wbsel.io.IN_uop(2) := csr.io.OUT_writebackUop
  writebackUop(0) := port0wbsel.io.OUT_uop

  // ** Port 1: ALU / DIV / CSR
  dispatcher(1).io.IN_uop <> readRegUop(1)

  alu1.io.IN_flush := flush
  div.io.IN_flush := flush

  alu1.io.IN_readRegUop <> dispatcher(1).io.OUT_uop(0)
  div.io.IN_readRegUop  <> dispatcher(1).io.OUT_uop(1)

  val port1wbsel = Module(new WritebackSel(2))
  port1wbsel.io.IN_uop(0) := alu1.io.OUT_writebackUop
  port1wbsel.io.IN_uop(1) := div.io.OUT_writebackUop
  writebackUop(1) := port1wbsel.io.OUT_uop

  // ** Port 2: LSU
  agu.io.IN_readRegUop <> readRegUop(2)

  agu.io.OUT_TLBReq <> dtlb.io.IN_TLBReq
  agu.io.IN_TLBResp <> dtlb.io.OUT_TLBResp

  dtlb.io.IN_PTWResp <> ptw.io.OUT_PTWResp
  dtlb.io.IN_TLBFlush := TLBFlush
  ptw.io.IN_VMCSR := csr.io.OUT_VMCSR
  ptw.io.IN_writebackUop <> writebackUop(2)
  ptw.io.IN_TLBFlush := TLBFlush
  ptw.io.IN_loadNegAck <> lsu.io.OUT_loadNegAck

  agu.io.IN_VMCSR := csr.io.OUT_VMCSR
  agu.io.OUT_PTWReq <> ptw.io.IN_PTWReq(1)

  agu.io.OUT_writebackUop <> writebackUop(3)
  agu.io.IN_PTWResp <> ptw.io.OUT_PTWResp
  agu.io.IN_flush := flush
  
  xtvalRecoder.io.IN_flush := flush
  xtvalRecoder.io.IN_tval := agu.io.OUT_xtvalRec

  loadArb.io.IN_AGUUop <> loadQueue.io.OUT_ldUop
  loadArb.io.IN_PTWUop <> ptw.io.OUT_PTWUop
  lsu.io.IN_loadUop.valid := loadArb.io.OUT_AGUUop.valid
  lsu.io.IN_loadUop.bits := loadArb.io.OUT_AGUUop.bits
  lsu.io.IN_flush := flush
  loadArb.io.OUT_AGUUop.ready := true.B
  
  val aguUop = Wire(Valid(new AGUUop))
  dontTouch(aguUop)

  aguUop <> agu.io.OUT_AGUUop
  loadQueue.io.IN_AGUUop <> aguUop
  loadQueue.io.IN_negAck <> lsu.io.OUT_loadNegAck
  loadQueue.io.IN_robTailPtr := rob.io.OUT_robTailPtr
  loadQueue.io.IN_commitLdqPtr := rob.io.OUT_ldqTailPtr
  loadQueue.io.IN_commitStqPtr := rob.io.OUT_stqTailPtr
  loadQueue.io.IN_flush := flush

  storeQueue.io.IN_AGUUop <> aguUop
  storeQueue.io.IN_robTailPtr := rob.io.OUT_robTailPtr
  storeQueue.io.IN_commitStqPtr := rob.io.OUT_stqTailPtr
  storeQueue.io.IN_flush := flush

  storeBuffer.io.IN_storeUop <> storeQueue.io.OUT_stUop
  storeBuffer.io.OUT_storeUop <> lsu.io.IN_storeUop
  storeBuffer.io.IN_storeAck <> lsu.io.OUT_storeAck

  writebackUop(2) := lsu.io.OUT_writebackUop

  // ** Store Queue Bypass
  lsu.io.IN_storeBypassResp <> storeQueue.io.OUT_storeBypassResp
  storeQueue.io.IN_storeBypassReq <> lsu.io.OUT_storeBypassReq

  // ** Store Buffer Bypass
  lsu.io.IN_storeBufferBypassResp <> storeBuffer.io.OUT_storeBypassResp
  storeBuffer.io.IN_storeBypassReq <> lsu.io.OUT_storeBypassReq

  // ** Internal MMIO
  lsu.io.OUT_mmioReq <> internalMMIO.io.IN_mmioReq
  lsu.io.IN_mmioResp <> internalMMIO.io.OUT_mmioResp

  // ** Cache 
  lsu.io.OUT_tagReq <> dcache.io.IN_tagReq
  lsu.io.OUT_dataReq <> dcache.io.IN_dataReq
  lsu.io.IN_tagResp <> dcache.io.OUT_tagResp
  lsu.io.IN_dataResp <> dcache.io.OUT_dataResp
  lsu.io.IN_mshrs <> cacheController.io.OUT_MSHR
  lsu.io.OUT_cacheCtrlUop <> cacheController.io.IN_cacheCtrlUop(0)
  lsu.io.OUT_uncacheUop <> cacheController.io.IN_cacheCtrlUop(1)
  lsu.io.IN_memLoadFoward <> cacheController.io.OUT_memLoadFoward
  lsu.io.IN_uncacheStoreResp := cacheController.io.OUT_uncacheStoreResp

  dcache.io.IN_ctrlDataRead <> cacheController.io.OUT_DDataRead
  dcache.io.IN_ctrlDataWrite <> cacheController.io.OUT_DDataWrite
  dcache.io.OUT_ctrlDataResp <> cacheController.io.IN_DDataResp

  // * AXI4 master
  arbiter.io.IFUMaster <> ifu.io.master
  arbiter.io.LSUMaster <> cacheController.io.OUT_axi

  // AXI4 slave
  io.slave.awready := false.B
  io.slave.arready := false.B
  io.slave.wready  := false.B

  io.slave.bvalid := false.B
  io.slave.bresp  := 0.U
  io.slave.bid    := 0.U

  io.slave.rdata  := 0.U
  io.slave.rvalid := false.B
  io.slave.rresp  := 0.U
  io.slave.rid    := 0.U
  io.slave.rlast  := true.B
}