package Common

import chisel3._
import chisel3.util._

class DebugMemArbiter(implicit val conf: SodorConfiguration) extends Module {
   val io = IO(new Bundle {
      val debug = Flipped(new MemPortIo(conf.xprlen))
      val serial = Flipped(new MemPortIo(conf.xprlen))
      val mem = new MemPortIo(conf.xprlen)
   })

   // For now, we will just give priority to the debug port. In the future, we can
   // add some logic to give priority to the serial port when it is active.
   
   val useSerial = io.serial.req.valid

   io.mem.req.valid := io.debug.req.valid || io.serial.req.valid
   io.mem.req.bits := Mux(useSerial, io.serial.req.bits, io.debug.req.bits)

   io.serial.req.ready := useSerial && io.mem.req.ready
   io.debug.req.ready := !useSerial && io.mem.req.ready

   io.serial.resp.valid := useSerial && io.mem.resp.valid
   io.serial.resp.bits := io.mem.resp.bits

   io.debug.resp.valid := !useSerial && io.mem.resp.valid
   io.debug.resp.bits := io.mem.resp.bits


}