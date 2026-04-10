package Sodor

import chisel3._
import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage}

import Common.{SodorConfiguration, SimDTM}

class Top extends Module 
{
   val io = IO(new Bundle{
      val success = Output(Bool())
    })

   implicit val sodor_conf = SodorConfiguration()
   val tile = Module(new SodorTile)
   val dtm = Module(new SimDTM).connect(clock, reset.asBool, tile.io.dmi, io.success)
}

object elaborate {
  def main(args: Array[String]): Unit = {
    (new ChiselStage).execute(args, Seq(ChiselGeneratorAnnotation(() => new Top)))
  }
}
