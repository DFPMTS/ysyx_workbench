import chisel3._
import chisel3.util._
import utils._
import chisel3.experimental.dataview._

class Core extends CoreModule {
  val io = IO(new Bundle {
    val master    = new AXI4ysyxSoC(AXI_DATA_WIDTH, AXI_ADDR_WIDTH)
    // val vPC = Output(UInt(XLEN.W))
    // val phyPC = Output(UInt(XLEN.W))
    // val fixRedirect = Output(new RedirectSignal)
    // val fetchRedirect = Output(new RedirectSignal)
    // val backendRedirect = Output(new RedirectSignal)
    // val flagOp = Output(Valid(new FlagUop))
    // val trapCSR = Output(new TrapCSR)
    // val vPCNext = Output(UInt(XLEN.W))
    // val vPCNextValid = Output(Bool())
    // val prediction = Output(new Prediction)
    // val fetchCanContinue = Output(Bool())
    // val fetchGroup = Output(new FetchGroup)
    // val phyPCValid = Output(Bool())
    // val IFUcacheMiss = Output(Bool())
    // val slave     = Flipped(new AXI4ysyxSoC(AXI_DATA_WIDTH, AXI_ADDR_WIDTH))
    // val interrupt = Input(Bool())
    // val commitUop = Valid(new CommitUop)
    // val loadUop = Valid(new AGUUop)
    // val storeUop = Valid(new AGUUop)
    // val instCommited = Output(UInt(64.W))
    // val robTailPtr = Output(RingBufferPtr(ROB_SIZE))
    // val robHeadPtr = Output(RingBufferPtr(ROB_SIZE))
    // val ldqTailPtr = Output(RingBufferPtr(LDQ_SIZE))
    // val stqTailPtr = Output(RingBufferPtr(STQ_SIZE))
    // val stqBasePtr = RingBufferPtr(STQ_SIZE)
    // val mshr = Vec(4, Output(new MSHR))
  })

  // * Internal MMIO 
  val internalMMIO = Module(new InternalMMIO)

  // * Cache
  val dcache = Module(new DCache)
  val icache = Module(new NewICache)
  val cacheController = Module(new CacheController)

  val ifu = Module(new IFU)
  val itlb = Module(new TLB(size = 1, id = 0))
  val idu = Module(new IDU)
  val rename = Module(new Rename)
  val rob = Module(new ROB)
  val scheduler = Module(new Scheduler)
  val iq = Seq(
    Module(new IssueQueue(Seq(FuType.ALU, FuType.MUL))),
    Module(new IssueQueue(Seq(FuType.ALU, FuType.DIV))),
    Module(new IssueQueue(Seq(FuType.ALU, FuType.CSR))),
    Module(new IssueQueue(Seq(FuType.LSU))),
  )
  val dispatcher = Seq(
    Module(new Dispatcher(
      Seq(Seq(FuType.ALU), Seq(FuType.MUL))
    )),
    Module(new Dispatcher(
      Seq(Seq(FuType.ALU), Seq(FuType.DIV))
    )),
    Module(new Dispatcher(
      Seq(Seq(FuType.ALU, FuType.BRU), Seq(FuType.CSR))
    )),
  )

  val readReg = Module(new ReadReg)
  val pReg = Module(new PReg)
  // * Port 0
  val alu0 = Module(new ALU(hasBru = false))
  val mul  = Module(new MUL)
  // * Port 1
  val alu1 = Module(new ALU(hasBru = false))
  val div  = Module(new DIV)
  // * Port 2
  val alu2 = Module(new ALU(hasBru = true))
  val csr  = Module(new CSR)
  // * Port 3
  val agu  = Module(new AGU)
  val loadQueue = Module(new LoadQueue)
  val storeQueue = Module(new StoreQueue)
  val storeBuffer = Module(new StoreBuffer)
  val amoUnit = Module(new AmoUnit)
  val lsu  = Module(new NewLSU)
  val dtlb = Module(new TLB(size = 1, id = 1))
  val ptw  = Module(new PTW)
  val loadArb = Module(new LoadArbiter)


  val xtvalRecorder = Module(new XtvalRecorder)
  val flagHandler = Module(new FlagHandler)
  val flush = Wire(Bool())
  val redirect = Wire(new RedirectSignal)
  dontTouch(redirect)
  val TLBFlush = Wire(Bool())


  // !
  // io.vPC := ifu.io.OUT_vPC
  // io.phyPC := ifu.io.OUT_phyPC
  // io.fetchRedirect := ifu.io.OUT_fetchRedirect
  // io.backendRedirect := redirect
  // io.fixRedirect := ifu.io.OUT_fixRedirect

  // io.vPCNext := ifu.io.OUT_vPCNext
  // io.vPCNextValid := ifu.io.OUT_vPCNextValid
  // io.prediction := ifu.io.OUT_prediction
  // io.fetchCanContinue := ifu.io.OUT_fetchCanContinue
  // io.flagOp := rob.io.OUT_flagUop
  // io.trapCSR := csr.io.OUT_trapCSR
  // io.fetchGroup := ifu.io.OUT_fetchGroup
  // io.IFUcacheMiss := ifu.io.OUT_cacheMiss
  // io.phyPCValid := ifu.io.OUT_phyPCValid
  // !

  // * decode
  val decodeUop = Wire(Vec(ISSUE_WIDTH, Decoupled(new DecodeUop)))
  dontTouch(decodeUop)

  // * rename
  val renameUop = Wire(Vec(ISSUE_WIDTH, new RenameUop))
  val renameRobValid = Wire(Vec(ISSUE_WIDTH, Bool()))
  val renameIQValid = Wire(Vec(ISSUE_WIDTH, Bool()))
  val renameIQReady = Wire(Vec(ISSUE_WIDTH, Bool()))
  dontTouch(renameUop)  
  dontTouch(renameRobValid)
  dontTouch(renameIQValid)
  dontTouch(renameIQReady)

  // * issue
  val issueUop = Wire(Vec(MACHINE_WIDTH, Decoupled(new RenameUop)))
  dontTouch(issueUop)
  val aluIssueUop = issueUop.take(NUM_ALU)
  
  // * read register
  val readRegUop = Wire(Vec(MACHINE_WIDTH, Decoupled(new ReadRegUop)))
  dontTouch(readRegUop)

  // * Zero-Cycle Forward
  val zeroCycleForward = Wire(Vec(NUM_ALU, Valid(new WritebackUop)))
  zeroCycleForward(0) := alu0.io.OUT_zeroCycleForward
  zeroCycleForward(1) := alu1.io.OUT_zeroCycleForward
  zeroCycleForward(2) := alu2.io.OUT_zeroCycleForward

  // * writeback
  val writebackUop = Wire(Vec(WRITEBACK_WIDTH, Valid(new WritebackUop)))
  for (i <- 0 until WRITEBACK_WIDTH) {
    writebackUop(i).valid := false.B
    writebackUop(i).bits := 0.U.asTypeOf(new WritebackUop)
  }
  dontTouch(writebackUop)

  // * commit
  val commitUop = Wire(Vec(COMMIT_WIDTH, Valid(new CommitUop)))
  dontTouch(commitUop)  

  // !
  // io.commitUop := commitUop(0)
  // io.loadUop := loadArb.io.OUT_AGUUop
  // io.storeUop := storeQueue.io.OUT_stUop
  // io.instCommited := rob.io.OUT_instCommited
  // io.robHeadPtr := rename.io.OUT_robHeadPtr
  // io.robTailPtr := rob.io.OUT_robTailPtr
  // io.ldqTailPtr := rob.io.OUT_ldqTailPtr
  // io.stqTailPtr := rob.io.OUT_stqTailPtr
  // io.stqBasePtr := storeQueue.io.OUT_stqBasePtr
  // io.mshr := cacheController.io.OUT_MSHR.take(4)
  //  !

  // * flag
  val flagUop = Wire(Valid(new FlagUop))
  dontTouch(flagUop)

  // * CSR ctrl
  val CSRCtrl = Wire(new CSRCtrl)
  dontTouch(CSRCtrl)

  // * IF
  ifu.io.redirect := redirect
  ifu.io.IN_btbUpdate := alu2.io.OUT_btbUpdate.get
  ifu.io.IN_phtUpdate := rob.io.OUT_phtUpdate
  ifu.io.IN_rasCommitUpdate := rob.io.OUT_rasUpdate
  ifu.io.IN_flushICache := flagHandler.io.OUT_flushICache
  ifu.io.IN_fetchEnable := !lsu.io.OUT_flushBusy
  ifu.io.OUT_TLBReq <> itlb.io.IN_TLBReq
  ifu.io.IN_TLBResp <> itlb.io.OUT_TLBResp
  ifu.io.OUT_PTWReq <> ptw.io.IN_PTWReq(0)
  ifu.io.IN_PTWResp <> ptw.io.OUT_PTWResp
  ifu.io.IN_VMCSR <> csr.io.OUT_VMCSR
  ifu.io.IN_trapCSR <> csr.io.OUT_trapCSR
  ifu.io.OUT_ITagRead <> icache.io.IN_tagRead
  ifu.io.OUT_ITagWrite <> icache.io.IN_tagWrite
  ifu.io.IN_ITagResp <> icache.io.OUT_tagResp
  ifu.io.OUT_IDataRead <> icache.io.IN_dataRead
  ifu.io.IN_IDataResp <> icache.io.OUT_dataResp
  ifu.io.IN_mshrs <> cacheController.io.OUT_MSHR
  ifu.io.OUT_cacheCtrlUop <> cacheController.io.IN_cacheCtrlUop(2)

  icache.io.IN_ctrlDataWrite <> cacheController.io.OUT_IDataWrite  

  itlb.io.IN_PTWResp <> ptw.io.OUT_PTWResp
  itlb.io.IN_TLBFlush := TLBFlush

  // * DE
  idu.io.IN_inst <> ifu.io.out
  idu.io.OUT_ready <> ifu.io.IN_ready
  idu.io.IN_flush := flush
  idu.io.OUT_decodeUop <> decodeUop

  // * Rename
  rename.io.IN_decodeUop <> decodeUop
  rename.io.IN_commitUop <> commitUop
  rename.io.IN_writebackUop <> writebackUop
  rename.io.IN_issueQueueReady := renameIQReady
  rename.io.IN_robEmpty := rob.io.OUT_robEmpty
  rename.io.IN_flush := flush
  rename.io.IN_robTailPtr := rob.io.OUT_robTailPtr

  rename.io.IN_ldqTailPtr := rob.io.OUT_ldqTailPtr
  rename.io.IN_stqTailPtr := rob.io.OUT_stqTailPtr

  rename.io.OUT_renameUop <> renameUop
  rename.io.OUT_robValid <> renameRobValid
  rename.io.OUT_issueQueueValid <> renameIQValid

  rename.io.IN_storeQueueEmpty := storeQueue.io.OUT_storeQueueEmpty
  rename.io.IN_storeBufferEmpty := storeBuffer.io.OUT_storeBufferEmpty

  // * ROB
  rob.io.IN_renameRobHeadPtr := rename.io.OUT_robHeadPtr
  for (i <- 0 until ISSUE_WIDTH) {
    rob.io.IN_renameUop(i).valid := renameRobValid(i)
    rob.io.IN_renameUop(i).bits := renameUop(i)
  }  
  rob.io.IN_writebackUop <> writebackUop
  rob.io.IN_flush := flush

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
  renameIQReady := scheduler.io.OUT_issueQueueReady

  // * Issue Queue
  for (i <- 0 until MACHINE_WIDTH) {
    iq(i).io.IN_renameUop <> scheduler.io.OUT_renameUop(i)
    for(j <- 0 until NUM_ALU) {
      iq(i).io.IN_issueUops(j).valid := aluIssueUop(j).valid
      iq(i).io.IN_issueUops(j).bits := aluIssueUop(j).bits
      
      iq(i).io.IN_readRegUop(j).valid := readRegUop(j).valid
      iq(i).io.IN_readRegUop(j).bits := readRegUop(j).bits
    }
    iq(i).io.IN_lsuWakeUp := lsu.io.OUT_wakeUp

    iq(i).io.IN_writebackUop := writebackUop
    iq(i).io.IN_robTailPtr := rob.io.OUT_robTailPtr
    iq(i).io.IN_ldqBasePtr := rob.io.OUT_ldqTailPtr
    iq(i).io.IN_stqBasePtr := storeQueue.io.OUT_stqBasePtr
    iq(i).io.IN_flush := flush
    iq(i).io.IN_idivBusy := (iq(1).io.OUT_issueUop.valid && iq(1).io.OUT_issueUop.bits.fuType === FuType.DIV) ||
                            (readRegUop(1).valid         && readRegUop(1).bits.fuType === FuType.DIV) ||
                            div.io.OUT_idivBusy
    issueUop(i) <> iq(i).io.OUT_issueUop
  }

  // * Read Register
  readReg.io.IN_issueUop <> issueUop
  readReg.io.IN_readRegVal := pReg.io.OUT_pRegVal
  for (i <- 0 until MACHINE_WIDTH) {    
    readRegUop(i) <> readReg.io.OUT_readRegUop(i)
    readRegUop(i).ready := false.B
  }
  readReg.io.IN_zeroCycleForward := zeroCycleForward
  readReg.io.IN_flush := flush

  // * PReg
  pReg.io.IN_pRegIndex := readReg.io.OUT_readRegIndex
  pReg.io.IN_writebackUop := writebackUop

  // * Execute
  // ** Port 0: ALU / MUL
  dispatcher(0).io.IN_uop <> readRegUop(0)

  alu0.io.IN_flush := flush
  mul.io.IN_flush := flush

  alu0.io.IN_readRegUop <> dispatcher(0).io.OUT_uop(0)
  mul.io.IN_readRegUop  <> dispatcher(0).io.OUT_uop(1)

  val port0wbsel = Module(new WritebackSel(2))
  port0wbsel.io.IN_uop(0) := alu0.io.OUT_writebackUop
  port0wbsel.io.IN_uop(1) := mul.io.OUT_writebackUop
  writebackUop(0) := port0wbsel.io.OUT_uop

  // ** Port 1: ALU / DIV
  dispatcher(1).io.IN_uop <> readRegUop(1)

  alu1.io.IN_flush := flush
  div.io.IN_flush := flush

  alu1.io.IN_readRegUop <> dispatcher(1).io.OUT_uop(0)
  div.io.IN_readRegUop  <> dispatcher(1).io.OUT_uop(1)

  val port1wbsel = Module(new WritebackSel(2))
  port1wbsel.io.IN_uop(0) := alu1.io.OUT_writebackUop
  port1wbsel.io.IN_uop(1) := div.io.OUT_writebackUop
  writebackUop(1) := port1wbsel.io.OUT_uop

  // ** Port 2: ALU / BRU / CSR
  dispatcher(2).io.IN_uop <> readRegUop(2)

  alu2.io.IN_flush := flush

  alu2.io.IN_readRegUop <> dispatcher(2).io.OUT_uop(0)
  csr.io.IN_readRegUop  <> dispatcher(2).io.OUT_uop(1)

  csr.io.IN_mtime := internalMMIO.io.OUT_mtime
  csr.io.IN_MTIP := internalMMIO.io.OUT_MTIP
  csr.io.IN_xtvalRec <> xtvalRecorder.io.OUT_tval
  csr.io.IN_CSRCtrl <> CSRCtrl

  val port2wbsel = Module(new WritebackSel(2))
  port2wbsel.io.IN_uop(0) := alu2.io.OUT_writebackUop
  port2wbsel.io.IN_uop(1) := csr.io.OUT_writebackUop
  writebackUop(2) := port2wbsel.io.OUT_uop

  // ** Port 3: LSU
  agu.io.IN_readRegUop <> readRegUop(3)

  agu.io.OUT_TLBReq <> dtlb.io.IN_TLBReq
  agu.io.IN_TLBResp <> dtlb.io.OUT_TLBResp

  dtlb.io.IN_PTWResp <> ptw.io.OUT_PTWResp
  dtlb.io.IN_TLBFlush := TLBFlush
  ptw.io.IN_VMCSR := csr.io.OUT_VMCSR
  ptw.io.IN_writebackUop <> writebackUop(3)
  ptw.io.IN_TLBFlush := TLBFlush
  ptw.io.IN_loadNegAck <> lsu.io.OUT_loadNegAck

  agu.io.IN_VMCSR := csr.io.OUT_VMCSR
  agu.io.OUT_PTWReq <> ptw.io.IN_PTWReq(1)

  agu.io.OUT_writebackUop <> writebackUop(4)
  agu.io.IN_PTWResp <> ptw.io.OUT_PTWResp
  agu.io.IN_flush := flush
  
  xtvalRecorder.io.IN_robTailPtr := rob.io.OUT_robTailPtr
  xtvalRecorder.io.IN_flush := flush
  xtvalRecorder.io.IN_tval := agu.io.OUT_xtvalRec

  loadArb.io.IN_stopPTWUop := amoUnit.io.OUT_amoActive
  loadArb.io.IN_AGUUop <> loadQueue.io.OUT_ldUop
  loadArb.io.IN_PTWUop <> ptw.io.OUT_PTWUop
  lsu.io.IN_loadUop <> loadArb.io.OUT_AGUUop
  lsu.io.IN_flush := flush
  lsu.io.IN_flushDCache := flagHandler.io.OUT_flushDCache
  
  lsu.io.IN_aguVirtualIndex <> agu.io.OUT_virtualIndex
  lsu.io.IN_aguLoadUop := loadQueue.io.OUT_aguLoadUop

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

  writebackUop(3) := lsu.io.OUT_writebackUop

  // ** Store Queue Bypass
  lsu.io.IN_storeBypassResp <> storeQueue.io.OUT_storeBypassResp
  storeQueue.io.IN_storeBypassReq <> lsu.io.OUT_storeBypassReq

  // ** Store Buffer Bypass
  lsu.io.IN_storeBufferBypassResp <> storeBuffer.io.OUT_storeBypassResp
  storeBuffer.io.IN_storeBypassReq <> lsu.io.OUT_storeBypassReq

  // ** Internal MMIO
  lsu.io.OUT_mmioReq <> internalMMIO.io.IN_mmioReq
  lsu.io.IN_mmioResp <> internalMMIO.io.OUT_mmioResp

  // ** Amo Unit
  amoUnit.io.IN_AGUUop <> aguUop
  amoUnit.io.OUT_amoUop <> lsu.io.IN_amoUop
  amoUnit.io.IN_amoAck <> lsu.io.OUT_amoAck
  amoUnit.io.IN_storeBufferEmpty := storeBuffer.io.OUT_storeBufferEmpty
  amoUnit.io.IN_storeQueueEmpty := storeQueue.io.OUT_storeQueueEmpty

  // ** Cache 
  lsu.io.OUT_tagRead <> dcache.io.IN_tagRead
  lsu.io.OUT_tagWrite <> dcache.io.IN_tagWrite
  lsu.io.OUT_dataRead <> dcache.io.IN_dataRead
  lsu.io.OUT_dataWrite <> dcache.io.IN_dataWrite
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
  io.master <> cacheController.io.OUT_axi.viewAs[AXI4ysyxSoC]

  // AXI4 slave
  // io.slave.awready := false.B
  // io.slave.arready := false.B
  // io.slave.wready  := false.B

  // io.slave.bvalid := false.B
  // io.slave.bresp  := 0.U
  // io.slave.bid    := 0.U

  // io.slave.rdata  := 0.U
  // io.slave.rvalid := false.B
  // io.slave.rresp  := 0.U
  // io.slave.rid    := 0.U
  // io.slave.rlast  := true.B
}