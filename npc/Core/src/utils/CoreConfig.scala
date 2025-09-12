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
  def NUM_PREG = 64
  def PREG_IDX_W = log2Up(NUM_PREG).W

  // * ROB 
  def ROB_SIZE = 33
  def ROB_IDX_W = log2Up(ROB_SIZE).W

  // * Issue Queue
  def IQ_SIZE = 16
  def IQ_IDX_W = log2Up(IQ_SIZE).W

  // * Load Queue
  def LDQ_SIZE = 16  
  def LDQ_IDX_W = log2Up(LDQ_SIZE).W

  // * Store Queue
  def STQ_SIZE = 16
  def STQ_IDX_W = log2Up(STQ_SIZE).W

  // * Flag
  def FLAG_W = 4.W

  // * Page Number Length
  def PAGE_NR_LEN = 20


  def ZERO = 0.U
}

trait CoreModule extends Module with HasCoreParameters with HasDecodeConfig

trait CoreBundle extends Bundle with HasCoreParameters with HasDecodeConfig