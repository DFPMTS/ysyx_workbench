package utils

import chisel3._
import chisel3.util._

trait HasDecodeConfig {
  def FuTypeWidth  = 4
  def OpcodeWidth  = 4
  def ImmTypeWidth = 4
  def FlagWidth    = 5

  def Y = 1.U(1.W)
  def N = 0.U(1.W)

}

trait HasCoreParameters extends HasDecodeConfig {
  def XLEN = 32
  def IDLEN = 4  

  // * Issue Width
  def ISSUE_WIDTH = 3
  def FETCH_BUFFER_SIZE = 4
  def MACHINE_WIDTH = 4
  def WRITEBACK_WIDTH = 5
  def COMMIT_WIDTH = 3

  // * TLB Entries
  val NUM_TLB = 32

  def USE_DUMMY_MUL_DIV = false
  // def USE_DUMMY_MUL_DIV = true

  def NUM_ALU = 3

  // * Int Mul Delay
  // * Delay = IMUL latency - ALU latency
  def IMUL_DELAY = 2

  // * Int Div Delay
  // def IDIV_DELAY = if (USE_DUMMY_MUL_DIV) 3 else (XLEN + 1)
  def IDIV_DELAY = (XLEN + 1)

  // * Physical Register
  def NUM_PREG = 64
  def PREG_IDX_W = log2Up(NUM_PREG).W

  // * ROB 
  def ROB_SIZE = 32
  def ROB_IDX_W = log2Up(ROB_SIZE).W

  // * Issue Queue
  def IQ_SIZE = 8
  def IQ_IDX_W = log2Up(IQ_SIZE).W

  // ! LDQ and STQ must be at least the IQ size, or there will be ldPtr/stPtr problem (flag wrap two time)
  // * Load Queue
  def LDQ_SIZE = 8
  def LDQ_IDX_W = log2Up(LDQ_SIZE).W

  // * Store Queue
  def STQ_SIZE = 8
  def STQ_IDX_W = log2Up(STQ_SIZE).W

  // * Flag
  def FLAG_W = FlagWidth.W

  // * Page Number Length
  def PAGE_NR_LEN = 20

  // * RAS Size
  def RAS_SIZE = 8

  // * BTB Size (of a single Bank)
  def BTB_INDEX_LEN = 7
  def BTB_SIZE = 1 << BTB_INDEX_LEN
  def BTB_TAG = XLEN - BTB_INDEX_LEN - log2Up(FETCH_WIDTH * 4) // ! C-extension

  // * PHT Size (of a single Bank)
  def PHT_INDEX_LEN = 11
  def PHT_SIZE = 1 << PHT_INDEX_LEN

  // * Global History Length
  def FOLDED_GHR_LEN = 5

  def GHR_LEN = 8

  // * Number of MSHR
  def NUM_MSHR = 4

  // * Cache Id Length
  def CACHE_ID_LEN = 1

  // * Cache Line Size in Bytes
  def CACHE_LINE_B = 64

  // * DCache Ways
  def DCACHE_WAYS = 2

  // * DCache Sets
  def DCACHE_SETS = 64

  // * DCache Tag Width
  def DCACHE_TAG = XLEN - log2Up(DCACHE_SETS) - log2Up(CACHE_LINE_B)

  // * Inst Fetch Width
  def FETCH_WIDTH = 4

  // * ICache Ways
  def ICACHE_WAYS = 2

  // * ICache Sets
  def ICACHE_SETS = 64

  // * ICache Tag Width
  def ICACHE_TAG = XLEN - log2Up(ICACHE_SETS) - log2Up(CACHE_LINE_B)

  // * AXI Data Width
  def AXI_DATA_WIDTH = 32

  // * AXI Addr Width
  def AXI_ADDR_WIDTH = 32

  def CacheLine = Vec(CACHE_LINE_B, UInt(8.W))
  def CacheSet = Vec(DCACHE_WAYS, CacheLine)

  def ZERO = 0.U
}

trait CoreModule extends Module with HasCoreParameters with HasDecodeConfig

trait CoreBundle extends Bundle with HasCoreParameters with HasDecodeConfig