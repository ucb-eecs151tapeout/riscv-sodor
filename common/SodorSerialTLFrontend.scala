package Common

import chisel3._

class SodorSerialTLFrontend(implicit val conf: SodorConfiguration) extends Module {
    val io = IO(new Bundle {
        val mem  = new MemPortIo(conf.xprlen)
    })

    val bridge = Module(new TLToSodorMemBridge())

    // Placeholder frontend
    bridge.io.tlReq.valid := false.B
    bridge.io.tlReq.bits := DontCare
    bridge.io.tlResp.ready := true.B
    
    io.mem <> bridge.io.mem
}