package utils

import chisel3._
import chisel3.util._

trait HasDecodeConfig {
  def FuTypeWidth  = 4
  def OpcodeWidth  = 4
  def ImmTypeWidth = 4
  def FlagWidth    = 4

  def Y = 1.U(1.W)
  def N = 0.U(1.W)
}

trait HasCoreParameters {
  def XLEN = 32
  def IDLEN = 4  

  // * Issue Width
  def ISSUE_WIDTH = 1
  def MACHINE_WIDTH = 4
  def COMMIT_WIDTH = 1


  // * Int Mul Delay
  // * Delay = IMUL latency - ALU latency
  def IMUL_DELAY = 2

  // * Int Div Delay
  def IDIV_DELAY = 3

  // * Physical Register
  def NUM_PREG = 48
  def PREG_IDX_W = log2Up(NUM_PREG).W

  // * ROB 
  def ROB_SIZE = 16
  def ROB_IDX_W = log2Up(ROB_SIZE).W

  // * Issue Queue
  def IQ_SIZE = 4
  def IQ_IDX_W = log2Up(IQ_SIZE).W

  // * Load Queue
  def LDQ_SIZE = 4
  def LDQ_IDX_W = log2Up(LDQ_SIZE).W

  // * Store Queue
  def STQ_SIZE = 4
  def STQ_IDX_W = log2Up(STQ_SIZE).W

  // * Flag
  def FLAG_W = 4.W

  // * Page Number Length
  def PAGE_NR_LEN = 20

  // * Number of MSHR
  def NUM_MSHR = 4

  // * Cache Id Length
  def CACHE_ID_LEN = 1

  // * Cache Line Size in Bytes
  def CACHE_LINE_B = 32

  // * DCache Ways
  def DCACHE_WAYS = 1

  // * DCache Sets
  def DCACHE_SETS = 4

  // * DCache Tag Width
  def DCACHE_TAG = XLEN - log2Up(DCACHE_SETS) - log2Up(CACHE_LINE_B)

  // * Inst Fetch Width
  def FETCH_WIDTH = 1

  // * ICache Ways
  def ICACHE_WAYS = 1

  // * ICache Sets
  def ICACHE_SETS = 4

  // * ICache Tag Width
  def ICACHE_TAG = XLEN - log2Up(ICACHE_SETS) - log2Up(CACHE_LINE_B)

  // * AXI Data Width
  def AXI_DATA_WIDTH = 256

  // * AXI Addr Width
  def AXI_ADDR_WIDTH = 32

  def CacheLine = Vec(CACHE_LINE_B, UInt(8.W))
  def CacheSet = Vec(DCACHE_WAYS, CacheLine)

  def ZERO = 0.U
}

trait CoreModule extends Module with HasCoreParameters with HasDecodeConfig

trait CoreBundle extends Bundle with HasCoreParameters with HasDecodeConfig