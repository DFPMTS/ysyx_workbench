import chisel3._
import chisel3.util._
import utils._

class FixBranchIO extends CoreBundle {
  val IN_fetchGroup = Flipped(Valid(new FetchGroup))
  val IN_prediction = Flipped(new Prediction)

  val OUT_btbUpdate = Valid(new BTBUpdate)
  val OUT_redirect = new RedirectSignal

  val OUT_fixBrOffset = UInt(log2Up(FETCH_WIDTH).W)
  val OUT_fixBrOffsetValid = Bool()
}

// * Predecode branch instructions
class FixBranch extends CoreModule {
  val io = IO(new FixBranchIO)

  val prediction = io.IN_prediction
  val brPredec = Seq.fill(FETCH_WIDTH)(Module(new BranchPredecode))
  val brInfos = Wire(Vec(FETCH_WIDTH, new BranchInfo))
  val PCs = Wire(Vec(FETCH_WIDTH, UInt(XLEN.W)))
  val snPCs = Wire(Vec(FETCH_WIDTH, UInt(XLEN.W)))
  val inPC = io.IN_fetchGroup.bits.pc
  val instValid = Wire(Vec(FETCH_WIDTH, Bool()))

  dontTouch(brInfos)

  val fixBrOffsetValid = WireInit(false.B)
  val fixBrOffset = WireInit(prediction.brOffset)

  // * Calculate PC for each Inst
  for(i <- 0 until FETCH_WIDTH) {
    instValid(i) := (if (FETCH_WIDTH == 1) true.B 
                     else (i.U >= inPC(log2Up(FETCH_WIDTH) - 1 + 2, 2) && (!prediction.brTaken || i.U <= prediction.brOffset)))
    PCs(i) := (if (FETCH_WIDTH == 1) inPC 
               else Cat(inPC(XLEN - 1, log2Up(FETCH_WIDTH) + 2), i.U(log2Up(FETCH_WIDTH).W), 0.U(2.W)))
  }
  // * Calculate snPC for each Inst
  for(i <- 0 until FETCH_WIDTH - 1) {
    snPCs(i) := PCs(i + 1)
  }
  snPCs(FETCH_WIDTH - 1) := Cat(inPC(XLEN - 1, log2Ceil(FETCH_WIDTH * 4)) + 1.U, 0.U(log2Ceil(FETCH_WIDTH * 4).W))

  // * Predecode
  for (i <- 0 until FETCH_WIDTH) {
    brPredec(i).io.IN_pc := PCs(i)
    brPredec(i).io.IN_inst := io.IN_fetchGroup.bits.insts(i)
    brInfos(i) := brPredec(i).io.OUT_brInfo
  }

  val redirectTarget = Wire(Vec(FETCH_WIDTH, UInt(XLEN.W)))
  val btbUpdate = Wire(Vec(FETCH_WIDTH, new BTBUpdate))
  val btbUpdateValid = Wire(Vec(FETCH_WIDTH, Bool()))
  val fixValid = Wire(Vec(FETCH_WIDTH, Bool()))
  
  // * Fix predictions
  for (i <- 0 until FETCH_WIDTH) {
    fixValid(i) := false.B
    btbUpdateValid(i) := false.B
    btbUpdate(i) := 0.U.asTypeOf(new BTBUpdate)
    redirectTarget(i) := 0.U
    
    val brInfo = brInfos(i)
    when(prediction.brOffset === i.U && prediction.brTaken) {
      // * Predicted: Taken Here
      when(!brInfo.hasBr) {
        // * Update BTB
        btbUpdateValid(i) := true.B
        btbUpdate(i).flush := true.B
        btbUpdate(i).pc := PCs(i)
        // * Redirect to the next instruction
        fixValid(i) := true.B
        redirectTarget(i) := snPCs(i)        
      }.otherwise /* brInfo.hasBr */ {        
        when(prediction.btbType =/= brInfo.predecBrType(2, 1) || 
            (brInfo.isDirectJump && prediction.btbTarget =/= brInfo.target)) {
          // * Branch Pos correct, but type/target wrong, update BTB
          when(!brInfo.isIndirect) {
            btbUpdateValid(i) := true.B
            btbUpdate(i).pc := PCs(i)
            btbUpdate(i).target := brInfo.target
            btbUpdate(i).brType := brInfo.predecBrType(2, 1)
          }

          // * Redirect to target
          fixValid(i) := true.B
          redirectTarget(i) := Mux(brInfo.isIndirect, snPCs(i), brInfo.target)
        }
      }
    }.otherwise /* Predicted: Not Taken here */ {
      when(!brInfo.hasBr) {
        // * Reality: No branch here
        when(prediction.btbValid) {
          // * Flush invalid entry
          btbUpdateValid(i) := true.B
          btbUpdate(i).pc := PCs(i)
          btbUpdate(i).flush := true.B
        }
      }.otherwise /* brInfo.hasBr */ {
        when(!brInfo.isIndirect) {
          btbUpdateValid(i) := true.B
          btbUpdate(i).pc := PCs(i)
          btbUpdate(i).target := brInfo.target
          btbUpdate(i).brType := brInfo.predecBrType(2, 1)  
        }
        when(brInfo.isDirectJump || (brInfo.isBranch && prediction.phtTaken(i))) {
          // * Redirect to target
          fixValid(i) := true.B
          redirectTarget(i) := brInfo.target
        }
      }
    }
  }

  val btbUpdateReg = Reg(Valid(new BTBUpdate))
  btbUpdateReg.valid := false.B
  
  io.OUT_redirect := 0.U.asTypeOf(io.OUT_redirect)
  io.OUT_btbUpdate := btbUpdateReg

  val fixCandidates = (fixValid.asUInt & instValid.asUInt)
  val hasFix = fixCandidates.orR
  val fixIndex = PriorityEncoder(fixCandidates)

  when(hasFix && io.IN_fetchGroup.valid) {
    fixBrOffsetValid := true.B
    fixBrOffset := fixIndex
    
    io.OUT_redirect.valid := true.B
    io.OUT_redirect.pc := redirectTarget(fixIndex)

    btbUpdateReg.valid := btbUpdateValid(fixIndex)
    btbUpdateReg.bits  := btbUpdate(fixIndex)
  }

  io.OUT_fixBrOffset := fixBrOffset
  io.OUT_fixBrOffsetValid := fixBrOffsetValid
}

/* 
Branch fix: 
  （1）btbValid，即预测此处有分支
    （1.1） !brInfo.hasBr, 实际没有分支
       -》[btbUpdate] 更新btb信息
       (1.1.1) prediction.taken, 需要redirect到预测分支指令的下一条
       (1.1.1) !prediction.taken, 
     (1.2) brInfo.hasBr, 实际有分支
       if 分支种类不对，分支目标不对 -》[btbUpdate] 更新btb信息
        (1.2.1) prediction.taken
          直接跳转：发起redirect
          分支：使用pht信息(brInfo.taken), 发起redirect
        else
          Nothing
  （2）!btbValid, 即预测此处没有分支
    （2.1）!brInfo.hasBr, 实际没有分支
      Nothing
    （2.2）brInfo.hasBr, 实际有分支      
      -》[btbUpdate] 更新btb信息
      (2.2.1) prediction.taken
        直接跳转：发起redirect
        分支：使用pht信息(brInfo.taken), 发起redirect
      (2.2.2) !prediction.taken
        Nothing？
 */