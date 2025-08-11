import chisel3._
import chisel3.util._
import utils._

class RenamingTableIO extends CoreBundle {
  // * Rename
  // ** Read (rs1 rs2)
  val IN_renameReadAReg = Flipped(Vec(ISSUE_WIDTH, Vec(2, UInt(5.W))))
  val OUT_renameReadPReg = Vec(ISSUE_WIDTH, Vec(2, UInt(PREG_IDX_W)))
  val OUT_renameReadReady = Vec(ISSUE_WIDTH, Vec(2, Bool()))
  // ** Write (rd)
  val IN_renameWriteValid = Flipped(Vec(ISSUE_WIDTH, Bool()))
  val IN_renameWriteAReg = Flipped(Vec(ISSUE_WIDTH, UInt(5.W)))  
  val IN_renameWritePReg = Flipped(Vec(ISSUE_WIDTH, UInt(PREG_IDX_W)))

  // * Writeback
  val IN_writebackValid = Flipped(Vec(WRITEBACK_WIDTH, Bool()))
  val IN_writebackPReg = Flipped(Vec(WRITEBACK_WIDTH, UInt(PREG_IDX_W)))
  
  // * Commit
  val IN_commitValid = Flipped(Vec(COMMIT_WIDTH, Bool()))
  val IN_commitAReg = Flipped(Vec(COMMIT_WIDTH, UInt(5.W)))
  val IN_commitPReg = Flipped(Vec(COMMIT_WIDTH, UInt(PREG_IDX_W)))
  // ** Previous Architectural Mapping
  val OUT_commitPrevPReg = Vec(COMMIT_WIDTH, UInt(PREG_IDX_W))

  val IN_flush = Input(Bool())
}

class RenamingTable extends CoreModule {
  val io = IO(new RenamingTableIO)

  // * Tables
  // ** Architectural Renaming Table
  val archTable = RegInit(VecInit(Seq.fill(32)(0.U(PREG_IDX_W))))
  // ** Speculative Renaming File
  val specTable = RegInit(VecInit(Seq.fill(32)(0.U(PREG_IDX_W))))
  // ** Ready Table
  val readyTable = RegInit(VecInit(Seq.fill(NUM_PREG)(true.B)))

  // * Rename Read
  for (i <- 0 until ISSUE_WIDTH) {
      for (j <- 0 until 2) {
        // * read PReg from specTable
        io.OUT_renameReadPReg(i)(j) := specTable(io.IN_renameReadAReg(i)(j))
        // * read readyTable
        io.OUT_renameReadReady(i)(j) := readyTable(io.OUT_renameReadPReg(i)(j))
        // * bypass from current cycle's writeback
        for (k <- 0 until MACHINE_WIDTH) {
          when (io.IN_writebackValid(k) && io.IN_writebackPReg(k) === io.OUT_renameReadPReg(i)(j)) {
            io.OUT_renameReadReady(i)(j) := true.B
          }
        }
        // * bypass from previous [0,i) instructions' write
        for (k <- 0 until i) {
          when (io.IN_renameReadAReg(i)(j) === io.IN_renameWriteAReg(k) && io.IN_renameWriteAReg(k) =/= 0.U && io.IN_renameWriteValid(k)) {
            io.OUT_renameReadPReg(i)(j) := io.IN_renameWritePReg(k)
            io.OUT_renameReadReady(i)(j) := false.B
          }
        }
      }    
  }
  // * Rename Write
  for (i <- 0 until ISSUE_WIDTH) {
    for (j <- 0 until 2) {
      when (io.IN_renameWriteValid(i) && io.IN_renameWriteAReg(i) =/= 0.U) {
        specTable(io.IN_renameWriteAReg(i)) := io.IN_renameWritePReg(i)
        readyTable(io.IN_renameWritePReg(i)) := false.B
      }
    }
  }

  // * Writeback
  for (i <- 0 until WRITEBACK_WIDTH) {  
    when (io.IN_writebackValid(i) && io.IN_writebackPReg(i) =/= 0.U) {
      readyTable(io.IN_writebackPReg(i)) := true.B
    }    
  }

  // * Commit
  for (i <- 0 until COMMIT_WIDTH) {
    io.OUT_commitPrevPReg(i) := archTable(io.IN_commitAReg(i))
    when (io.IN_commitValid(i) && io.IN_commitAReg(i) =/= 0.U) {
      archTable(io.IN_commitAReg(i)) := io.IN_commitPReg(i)      
    }    
  }

  // * Flush
  when (io.IN_flush) {
    specTable := archTable
    readyTable := VecInit(Seq.fill(NUM_PREG)(true.B))
  }
}