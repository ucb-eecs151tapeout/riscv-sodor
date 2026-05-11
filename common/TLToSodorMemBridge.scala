package Common

import chisel3._
import chisel3.util._

class SimpleTLReq(implicit val conf: SodorConfiguration) extends Bundle {
    val addr = UInt(conf.xprlen.W)
    val data = UInt(conf.xprlen.W)
    val isWrite = Bool()
}

class SimpleTLResp(implicit val conf: SodorConfiguration) extends Bundle {
    val data = UInt(conf.xprlen.W)
}

class TLToSodorMemBridge(implicit val conf: SodorConfiguration) extends Module {
    val io = IO(new Bundle {
        val tlReq = Flipped(Decoupled(new SimpleTLReq()))
        val tlResp = Decoupled(new SimpleTLResp())
        val mem = new MemPortIo(conf.xprlen)
    })

    val sIdle :: sWaitResp :: Nil = Enum(2)
    val state = RegInit(sIdle)

    io.tlReq.ready := state === sIdle && io.mem.req.ready

    io.tlResp.valid := state === sWaitResp && io.mem.resp.valid
    io.tlResp.bits.data := io.mem.resp.bits.data

    io.mem.req.valid := state === sIdle && io.tlReq.valid
    io.mem.req.bits := DontCare
    io.mem.req.bits.addr := io.tlReq.bits.addr
    io.mem.req.bits.data := io.tlReq.bits.data
    io.mem.req.bits.fcn := Mux(io.tlReq.bits.isWrite, 1.U, 0.U)

    when(io.tlReq.fire) {
        state := sWaitResp
    }

    when(io.tlResp.fire) {
        state := sIdle
    }


}