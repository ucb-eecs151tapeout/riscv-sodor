//**************************************************************************
// RISCV Processor Tile
//--------------------------------------------------------------------------
//

package Sodor
{

import chisel3._

import Common.{SodorConfiguration, DMIIO, AsyncScratchPadMemory, DebugModule, DebugMemArbiter}

class SodorTile(implicit val conf: SodorConfiguration) extends Module
{
   // connected here needs to change
   val io = IO(new Bundle {
      val dmi = Flipped(new DMIIO())
   })

   // notice that while the core is put into reset, the scratchpad needs to be
   // alive so that the Debug Module can load in the program.
   val debug = Module(new DebugModule())
   val core   = Module(new Core())
   core.io := DontCare
   val memory = Module(new AsyncScratchPadMemory(num_core_ports = 2))
   core.io.dmem <> memory.io.core_ports(0)
   core.io.imem <> memory.io.core_ports(1)


   // Arbiter inserted so a future Serial TL frontend can access the scratchpad
   // through the same debug memory port used by DebugModule, without modifying Core.
   
   val debugMemArb = Module(new DebugMemArbiter())

   debugMemArb.io.debug <> debug.io.debugmem
   debugMemArb.io.mem <> memory.io.debug_port


   debugMemArb.io.serial.req.valid := false.B
   debugMemArb.io.serial.req.bits := DontCare


   core.reset := debug.io.resetcore | reset.toBool
   debug.io.dmi <> io.dmi
}

}