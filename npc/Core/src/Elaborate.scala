import circt.stage._
import chisel3._
import chisel3.ActualDirection.Empty

object parseArgs {
  def apply(args: Array[String]) = {
    if (args.contains("Debug")) {
      Config.debug = true
    } else {
      Config.debug = false
    }
    Config.debug = false
    if (args.contains("npc")) {
      Config.resetPC = "h1000_0000".U
    } else {
      Config.resetPC = "h30000000".U
    }
  }
}

object Elaborate_npc extends App {
  parseArgs(args)

  val firtoolOptions = Array(
    "--lowering-options=" + List(
      // make yosys happy
      // see https://github.com/llvm/circt/blob/main/docs/VerilogGeneration.md
      "disallowLocalVariables",
      "disallowPackedArrays",
      "locationInfoStyle=wrapInAtSquareBracket",
      "disallowLocalVariables", "explicitBitcast", "disallowMuxInlining", "disallowExpressionInliningInPorts", "verifLabels",
    ).reduce(_ + "," + _),
    "-disable-all-randomization", 
    "-strip-debug-info",
    "-strip-fir-debug-info",
    "-O=release",
    "--ignore-read-enable-mem",
  )
  println("firtool version", chisel3.BuildInfo.firtoolVersion, chisel3.BuildInfo.version, chisel3.BuildInfo.scalaVersion )
  circt.stage.ChiselStage.emitSystemVerilogFile(new npc_top, Array("-td", "./vsrc", "--split-verilog", "--throw-on-first-error"), firtoolOptions)
}

object Elaborate_soc extends App {
  parseArgs(args)
  // def top = new multi
  // val generator = Seq(chisel3.stage.ChiselGeneratorAnnotation(() => top))
  // (new ChiselStage)
  //   .execute(Array("-td", "./vsrc", "--split-verilog"), generator :+ CIRCTTargetAnnotation(CIRCTTarget.Verilog))

  val firtoolOptions = Array(
    "--lowering-options=" + List(
      // make yosys happy
      // see https://github.com/llvm/circt/blob/main/docs/VerilogGeneration.md
      "disallowLocalVariables",
      "disallowPackedArrays",
      "locationInfoStyle=wrapInAtSquareBracket"
    ).reduce(_ + "," + _)
  )
  circt.stage.ChiselStage.emitSystemVerilogFile(new multi, Array("-td", "./vsrc", "--split-verilog"), firtoolOptions)
}

object Elaborate_nvboard extends App {
  parseArgs(args)
  def top       = new multi
  val generator = Seq(chisel3.stage.ChiselGeneratorAnnotation(() => top))
  (new ChiselStage)
    .execute(Array("-td", "./vsrc", "--split-verilog"), generator :+ CIRCTTargetAnnotation(CIRCTTarget.Verilog))
}
