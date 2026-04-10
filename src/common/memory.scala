//**************************************************************************
// Scratchpad Memory (asynchronous)
//--------------------------------------------------------------------------
//
// Christopher Celio
// 2013 Jun 12
//
// Provides a variable number of ports to the core, and one port to the Debug Module
//
// Assumes that if the port is ready, it will be performed immediately.
//
// Optionally uses synchronous read (default is async). For example, a 1-stage
// processor can only ever work using asynchronous memory!

package Common
{

import chisel3._
import chisel3.util.{DecoupledIO, ValidIO, log2Ceil, Cat, Fill, MuxCase}

import Constants._

trait MemoryOpConstants 
{
   val MT_X  = 0.asUInt(3.W)
   val MT_B  = 1.asUInt(3.W)
   val MT_H  = 2.asUInt(3.W)
   val MT_W  = 3.asUInt(3.W)
   val MT_D  = 4.asUInt(3.W)
   val MT_BU = 5.asUInt(3.W)
   val MT_HU = 6.asUInt(3.W)
   val MT_WU = 7.asUInt(3.W)

   val M_X   = "b0".asUInt(1.W)
   val M_XRD = "b0".asUInt(1.W) // int load
   val M_XWR = "b1".asUInt(1.W) // int store

   val DPORT = 0
   val IPORT = 1
}

class Rport(val addrWidth : Int,val dataWidth : Int) extends Bundle{
   val addr = Input(UInt(addrWidth.W))
   val data = Output(UInt(dataWidth.W))
}

class Wport(val addrWidth : Int,val dataWidth : Int) extends Bundle{
   val maskWidth = dataWidth/8
   val addr = Input(UInt(addrWidth.W))
   val data = Input(UInt(dataWidth.W))
   val mask = Input(UInt(maskWidth.W))
   val en = Input(Bool())
}

class d2h2i1(val addrWidth : Int) extends Bundle{
   val dataInstr = Vec(2,new  Rport(addrWidth,32))
   val hw = new  Wport(addrWidth,32)
   val dw = new  Wport(addrWidth,32)
   val hr = new  Rport(addrWidth,32)
   val clk = Input(Clock()) 
}

class AsyncReadMem(val addrWidth : Int) extends BlackBox{
   val io = IO(new d2h2i1(addrWidth))
}

class SyncMem(val addrWidth : Int) extends BlackBox{
   val io = IO(new d2h2i1(addrWidth))
}

class Sram22PortIO extends Bundle {
   val clk = Input(Clock())
   val rstb = Input(Bool())
   val ce = Input(Bool())
   val we = Input(Bool())
   val wmask = Input(UInt(4.W))
   val addr = Input(UInt(11.W))
   val din = Input(UInt(32.W))
   val dout = Output(UInt(32.W))
}

class sram22_2048x32m8w8 extends BlackBox {
   val io = IO(new Sram22PortIO)
}

class ScratchPadIo(val num_core_ports: Int)(implicit val conf: SodorConfiguration) extends Bundle {
   val core_ports = Vec(num_core_ports, Flipped(new MemPortIo(data_width = conf.xprlen)))
   val debug_port = Flipped(new MemPortIo(data_width = 32))
   val reset_core = Input(Bool())
}

// Simple async behavioral model for RTL simulation.
class AsyncScratchPadMemoryModel(val num_core_ports: Int, val num_bytes: Int)(implicit val conf: SodorConfiguration) extends Module {
   require(num_bytes == 16384, "Async scratchpad model supports exactly 16 KiB")
   val io = IO(new ScratchPadIo(num_core_ports))

   val num_bytes_per_line = 4
   val num_lines = num_bytes / num_bytes_per_line

   val mem = Mem(num_lines, Vec(4, UInt(8.W)))

   def wordAddr(addr: UInt): UInt = addr(13, 2)
   def byteOffset(addr: UInt): UInt = addr(1, 0)
   def writeData(addr: UInt, data: UInt): UInt = data << (byteOffset(addr) << 3)
   def writeMask(addr: UInt, typ: UInt): UInt = {
      val mask = (Mux(typ === MT_B, 1.U(4.W) << byteOffset(addr),
       Mux(typ === MT_H, 3.U(4.W) << byteOffset(addr), "b1111".U(4.W))) & "b1111".U)
      mask(3,0)
   }
   def loadData(data: UInt, typ: UInt, offset: UInt): UInt = {
      val shifted = data >> (offset << 3)
      MuxCase(shifted, Array(
         (typ === MT_B)  -> Cat(Fill(24, shifted(7)), shifted(7,0)),
         (typ === MT_H)  -> Cat(Fill(16, shifted(15)), shifted(15,0)),
         (typ === MT_BU) -> Cat(Fill(24, 0.U), shifted(7,0)),
         (typ === MT_HU) -> Cat(Fill(16, 0.U), shifted(15,0))
      ))
   }

   for (i <- 0 until num_core_ports) {
      io.core_ports(i).req.ready := true.B
      io.core_ports(i).resp.valid := false.B
      io.core_ports(i).resp.bits.data := 0.U
   }
   io.debug_port.req.ready := true.B
   io.debug_port.resp.valid := false.B
   io.debug_port.resp.bits.data := 0.U

   val core_active = !io.reset_core

   if (num_core_ports == 2) {
      val dreq = io.core_ports(DPORT).req.bits
      val dvalid = core_active && io.core_ports(DPORT).req.valid
      val dwrite = dvalid && (dreq.fcn === M_XWR)
      val dbgreq = io.debug_port.req.bits
      val dbgvalid = io.debug_port.req.valid
      val dbgwrite = dbgvalid && (dbgreq.fcn === M_XWR)
      val dbgread = dbgvalid && !dbgwrite
      val ivalid = core_active && io.core_ports(IPORT).req.valid && !dbgvalid
      val ireq = io.core_ports(IPORT).req.bits

      when (dbgwrite) {
         val wdata = writeData(dbgreq.addr, dbgreq.data)
         val wmask = "b1111".U(4.W)
         mem.write(wordAddr(dbgreq.addr), wdata.asTypeOf(Vec(4, UInt(8.W))), wmask.asBools)
      } .elsewhen (dwrite) {
         val wdata = writeData(dreq.addr, dreq.data)
         val wmask = writeMask(dreq.addr, dreq.typ)
         mem.write(wordAddr(dreq.addr), wdata.asTypeOf(Vec(4, UInt(8.W))), wmask.asBools)
      }

      val imem_rdata = mem.read(wordAddr(ireq.addr)).asUInt
      val dmem_rdata = mem.read(wordAddr(dreq.addr)).asUInt
      val dbg_rdata = mem.read(wordAddr(dbgreq.addr)).asUInt

      io.core_ports(IPORT).resp.valid := ivalid
      io.core_ports(IPORT).resp.bits.data := imem_rdata
      io.core_ports(DPORT).resp.valid := dvalid
      io.core_ports(DPORT).resp.bits.data := loadData(dmem_rdata, dreq.typ, byteOffset(dreq.addr))
      io.debug_port.resp.valid := dbgread
      io.debug_port.resp.bits.data := loadData(dbg_rdata, dbgreq.typ, byteOffset(dbgreq.addr))
   } else {
      val creq = io.core_ports(0).req.bits
      val cvalid = core_active && io.core_ports(0).req.valid
      val cwrite = cvalid && (creq.fcn === M_XWR)
      val dbgreq = io.debug_port.req.bits
      val dbgvalid = io.debug_port.req.valid
      val dbgwrite = dbgvalid && (dbgreq.fcn === M_XWR)
      val dbgread = dbgvalid && !dbgwrite

      when (dbgwrite) {
         val wdata = writeData(dbgreq.addr, dbgreq.data)
         val wmask = "b1111".U(4.W)
         mem.write(wordAddr(dbgreq.addr), wdata.asTypeOf(Vec(4, UInt(8.W))), wmask.asBools)
      } .elsewhen (cwrite) {
         val wdata = writeData(creq.addr, creq.data)
         val wmask = writeMask(creq.addr, creq.typ)
         mem.write(wordAddr(creq.addr), wdata.asTypeOf(Vec(4, UInt(8.W))), wmask.asBools)
      }

      val core_rdata = mem.read(wordAddr(creq.addr)).asUInt
      val dbg_rdata = mem.read(wordAddr(dbgreq.addr)).asUInt

      io.core_ports(0).resp.valid := cvalid
      io.core_ports(0).resp.bits.data := loadData(core_rdata, creq.typ, byteOffset(creq.addr))
      io.debug_port.resp.valid := dbgread
      io.debug_port.resp.bits.data := loadData(dbg_rdata, dbgreq.typ, byteOffset(dbgreq.addr))
   }
}

// from the pov of the datapath
class MemPortIo(val data_width: Int)(implicit val conf: SodorConfiguration) extends Bundle 
{
   val req    = new DecoupledIO(new MemReq(data_width))
   val resp   = Flipped(new ValidIO(new MemResp(data_width)))
}

class MemReq(val data_width: Int)(implicit val conf: SodorConfiguration) extends Bundle
{
   val addr = Output(UInt(conf.xprlen.W))
   val data = Output(UInt(data_width.W))
   val fcn  = Output(UInt(M_X.getWidth.W))  // memory function code
   val typ  = Output(UInt(MT_X.getWidth.W)) // memory type
}

class MemResp(val data_width: Int) extends Bundle
{
   val data = Output(UInt(data_width.W))
}

abstract class Sram22ScratchPadBase(val num_core_ports: Int, val num_bytes: Int, desc: String)(implicit val conf: SodorConfiguration) extends Module
{
   require(num_bytes == 16384, "SRAM22-backed Sodor scratchpad currently supports exactly 16 KiB")

   val io = IO(new Bundle
   {
      val core_ports = Vec(num_core_ports, Flipped(new MemPortIo(data_width = conf.xprlen)) )
      val debug_port = Flipped(new MemPortIo(data_width = 32))
      val reset_core = Input(Bool())
   })

   val num_bytes_per_line = 4
   val num_lines = num_bytes / num_bytes_per_line
   println("\n    Sodor Tile: creating " + desc + " Scratchpad Memory of size " + num_lines*num_bytes_per_line/1024 + " kB using SRAM22 macros\n")

   for (i <- 0 until num_core_ports) {
      io.core_ports(i).req.ready := true.B
      io.core_ports(i).resp.valid := false.B
      io.core_ports(i).resp.bits.data := 0.U
   }
   io.debug_port.req.ready := true.B
   io.debug_port.resp.valid := false.B
   io.debug_port.resp.bits.data := 0.U

   def wordAddr(addr: UInt): UInt = addr(13, 2)
   def byteOffset(addr: UInt): UInt = addr(1, 0)
   def writeData(addr: UInt, data: UInt): UInt = data << (byteOffset(addr) << 3)
   def writeMask(addr: UInt, typ: UInt): UInt = {
      val mask = (Mux(typ === MT_B, 1.U(4.W) << byteOffset(addr),
       Mux(typ === MT_H, 3.U(4.W) << byteOffset(addr), "b1111".U(4.W))) & "b1111".U)
      mask(3,0)
   }
   def loadData(data: UInt, typ: UInt, offset: UInt): UInt = {
      val shifted = data >> (offset << 3)
      MuxCase(shifted, Array(
         (typ === MT_B)  -> Cat(Fill(24, shifted(7)), shifted(7,0)),
         (typ === MT_H)  -> Cat(Fill(16, shifted(15)), shifted(15,0)),
         (typ === MT_BU) -> Cat(Fill(24, 0.U), shifted(7,0)),
         (typ === MT_HU) -> Cat(Fill(16, 0.U), shifted(15,0))
      ))
   }
   def initMacro(mem: sram22_2048x32m8w8): Unit = {
      mem.io.clk := clock
      mem.io.rstb := true.B
      mem.io.ce := false.B
      mem.io.we := false.B
      mem.io.wmask := 0.U
      mem.io.addr := 0.U
      mem.io.din := 0.U
   }

   def bankSel(addr: UInt): Bool = addr(13)
   def bankAddr(addr: UInt): UInt = addr(12, 2)

   if (num_core_ports == 2) {
      val data_sram_0 = Module(new sram22_2048x32m8w8).suggestName("sram_dmem_0")
      val data_sram_1 = Module(new sram22_2048x32m8w8).suggestName("sram_dmem_1")
      val inst_sram_0 = Module(new sram22_2048x32m8w8).suggestName("sram_imem_0")
      val inst_sram_1 = Module(new sram22_2048x32m8w8).suggestName("sram_imem_1")
      initMacro(data_sram_0)
      initMacro(data_sram_1)
      initMacro(inst_sram_0)
      initMacro(inst_sram_1)

      val core_active = !io.reset_core
      val dreq = io.core_ports(DPORT).req.bits
      val dvalid = core_active && io.core_ports(DPORT).req.valid
      val dwrite = dvalid && (dreq.fcn === M_XWR)
      val ireq = io.core_ports(IPORT).req.bits
      val ivalid = core_active && io.core_ports(IPORT).req.valid

      val dbgreq = io.debug_port.req.bits
      val dbgvalid = io.debug_port.req.valid
      val dbgwrite = dbgvalid && (dbgreq.fcn === M_XWR)
      val dbgread = dbgvalid && !dbgwrite

      val scheduled_imem = Wire(Bool())
      val scheduled_dmem = Wire(Bool())
      val scheduled_debug = Wire(Bool())
      scheduled_imem := false.B
      scheduled_dmem := false.B
      scheduled_debug := false.B

      val dresp_type = WireDefault(MT_W)
      val dresp_offset = WireDefault(0.U(2.W))
      val dresp_bank = WireDefault(false.B)
      val dbgresp_type = WireDefault(MT_W)
      val dbgresp_offset = WireDefault(0.U(2.W))
      val dbgresp_bank = WireDefault(false.B)
      val iresp_bank = WireDefault(false.B)

      // Instruction fetch is independent from data access (separate macros).
      when (ivalid) {
         val i_bank = bankSel(ireq.addr)
         inst_sram_0.io.ce := !i_bank
         inst_sram_1.io.ce := i_bank
         inst_sram_0.io.addr := bankAddr(ireq.addr)
         inst_sram_1.io.addr := bankAddr(ireq.addr)
         iresp_bank := i_bank
         scheduled_imem := true.B
      }

      // Debug writes initialize both instruction and data memories.
      when (dbgwrite) {
         val dbg_bank_sel = bankSel(dbgreq.addr)
         val dbg_wdata = writeData(dbgreq.addr, dbgreq.data)
         inst_sram_0.io.ce := !dbg_bank_sel
         inst_sram_1.io.ce := dbg_bank_sel
         inst_sram_0.io.we := !dbg_bank_sel
         inst_sram_1.io.we := dbg_bank_sel
         inst_sram_0.io.addr := bankAddr(dbgreq.addr)
         inst_sram_1.io.addr := bankAddr(dbgreq.addr)
         inst_sram_0.io.din := dbg_wdata
         inst_sram_1.io.din := dbg_wdata
         inst_sram_0.io.wmask := "b1111".U
         inst_sram_1.io.wmask := "b1111".U
         data_sram_0.io.ce := !dbg_bank_sel
         data_sram_1.io.ce := dbg_bank_sel
         data_sram_0.io.we := !dbg_bank_sel
         data_sram_1.io.we := dbg_bank_sel
         data_sram_0.io.addr := bankAddr(dbgreq.addr)
         data_sram_1.io.addr := bankAddr(dbgreq.addr)
         data_sram_0.io.din := dbg_wdata
         data_sram_1.io.din := dbg_wdata
         data_sram_0.io.wmask := "b1111".U
         data_sram_1.io.wmask := "b1111".U
         scheduled_debug := false.B
      } .elsewhen (dbgread) {
         val dbg_bank_sel = bankSel(dbgreq.addr)
         data_sram_0.io.ce := !dbg_bank_sel
         data_sram_1.io.ce := dbg_bank_sel
         data_sram_0.io.addr := bankAddr(dbgreq.addr)
         data_sram_1.io.addr := bankAddr(dbgreq.addr)
         dbgresp_type := dbgreq.typ
         dbgresp_offset := byteOffset(dbgreq.addr)
         dbgresp_bank := dbg_bank_sel
         scheduled_debug := true.B
      } .elsewhen (dwrite) {
         val d_bank_sel = bankSel(dreq.addr)
         val d_wdata = writeData(dreq.addr, dreq.data)
         val d_wmask = writeMask(dreq.addr, dreq.typ)
         data_sram_0.io.ce := !d_bank_sel
         data_sram_1.io.ce := d_bank_sel
         data_sram_0.io.we := !d_bank_sel
         data_sram_1.io.we := d_bank_sel
         data_sram_0.io.addr := bankAddr(dreq.addr)
         data_sram_1.io.addr := bankAddr(dreq.addr)
         data_sram_0.io.din := d_wdata
         data_sram_1.io.din := d_wdata
         data_sram_0.io.wmask := d_wmask
         data_sram_1.io.wmask := d_wmask
         dresp_bank := d_bank_sel
         scheduled_dmem := true.B
      } .elsewhen (dvalid) {
         val d_bank_sel = bankSel(dreq.addr)
         data_sram_0.io.ce := !d_bank_sel
         data_sram_1.io.ce := d_bank_sel
         data_sram_0.io.addr := bankAddr(dreq.addr)
         data_sram_1.io.addr := bankAddr(dreq.addr)
         dresp_type := dreq.typ
         dresp_offset := byteOffset(dreq.addr)
         dresp_bank := d_bank_sel
         scheduled_dmem := true.B
      }

      val inst_dout = Mux(RegNext(iresp_bank, false.B), inst_sram_1.io.dout, inst_sram_0.io.dout)
      val data_dbg_dout = Mux(RegNext(dbgresp_bank, false.B), data_sram_1.io.dout, data_sram_0.io.dout)
      val data_core_dout = Mux(RegNext(dresp_bank, false.B), data_sram_1.io.dout, data_sram_0.io.dout)
      val dresp_type_q = RegNext(dresp_type, MT_W)
      val dresp_offset_q = RegNext(dresp_offset, 0.U(2.W))
      val dbgresp_type_q = RegNext(dbgresp_type, MT_W)
      val dbgresp_offset_q = RegNext(dbgresp_offset, 0.U(2.W))
      io.core_ports(IPORT).resp.valid := RegNext(scheduled_imem, false.B)
      io.core_ports(IPORT).resp.bits.data := inst_dout
      io.core_ports(DPORT).resp.valid := RegNext(scheduled_dmem, false.B)
      io.core_ports(DPORT).resp.bits.data := loadData(data_core_dout, dresp_type_q, dresp_offset_q)
      io.debug_port.resp.valid := RegNext(scheduled_debug, false.B)
      io.debug_port.resp.bits.data := loadData(data_dbg_dout, dbgresp_type_q, dbgresp_offset_q)
   } else {
      val shared_sram_0 = Module(new sram22_2048x32m8w8).suggestName("sram_mem_0")
      val shared_sram_1 = Module(new sram22_2048x32m8w8).suggestName("sram_mem_1")
      initMacro(shared_sram_0)
      initMacro(shared_sram_1)

      val core_active = !io.reset_core
      val creq = io.core_ports(0).req.bits
      val cvalid = core_active && io.core_ports(0).req.valid
      val cwrite = cvalid && (creq.fcn === M_XWR)
      val dbgreq = io.debug_port.req.bits
      val dbgvalid = io.debug_port.req.valid
      val dbgwrite = dbgvalid && (dbgreq.fcn === M_XWR)

      val scheduled_core = Wire(Bool())
      val scheduled_debug = Wire(Bool())
      scheduled_core := false.B
      scheduled_debug := false.B

      val core_resp_type = WireDefault(MT_W)
      val core_resp_offset = WireDefault(0.U(2.W))
      val dbgresp_type = WireDefault(MT_W)
      val dbgresp_offset = WireDefault(0.U(2.W))

      when (dbgwrite) {
         shared_sram_0.io.ce := !bankSel(dbgreq.addr)
         shared_sram_1.io.ce := bankSel(dbgreq.addr)
         shared_sram_0.io.we := !bankSel(dbgreq.addr)
         shared_sram_1.io.we := bankSel(dbgreq.addr)
         shared_sram_0.io.addr := bankAddr(dbgreq.addr)
         shared_sram_1.io.addr := bankAddr(dbgreq.addr)
         shared_sram_0.io.din := writeData(dbgreq.addr, dbgreq.data)
         shared_sram_1.io.din := writeData(dbgreq.addr, dbgreq.data)
         shared_sram_0.io.wmask := "b1111".U
         shared_sram_1.io.wmask := "b1111".U
         scheduled_debug := false.B
      } .elsewhen (dbgvalid) {
         shared_sram_0.io.ce := !bankSel(dbgreq.addr)
         shared_sram_1.io.ce := bankSel(dbgreq.addr)
         shared_sram_0.io.addr := bankAddr(dbgreq.addr)
         shared_sram_1.io.addr := bankAddr(dbgreq.addr)
         dbgresp_type := dbgreq.typ
         dbgresp_offset := byteOffset(dbgreq.addr)
         scheduled_debug := true.B
      } .elsewhen (cwrite) {
         shared_sram_0.io.ce := !bankSel(creq.addr)
         shared_sram_1.io.ce := bankSel(creq.addr)
         shared_sram_0.io.we := !bankSel(creq.addr)
         shared_sram_1.io.we := bankSel(creq.addr)
         shared_sram_0.io.addr := bankAddr(creq.addr)
         shared_sram_1.io.addr := bankAddr(creq.addr)
         shared_sram_0.io.din := writeData(creq.addr, creq.data)
         shared_sram_1.io.din := writeData(creq.addr, creq.data)
         shared_sram_0.io.wmask := writeMask(creq.addr, creq.typ)
         shared_sram_1.io.wmask := writeMask(creq.addr, creq.typ)
         scheduled_core := true.B
      } .elsewhen (cvalid) {
         shared_sram_0.io.ce := !bankSel(creq.addr)
         shared_sram_1.io.ce := bankSel(creq.addr)
         shared_sram_0.io.addr := bankAddr(creq.addr)
         shared_sram_1.io.addr := bankAddr(creq.addr)
         core_resp_type := creq.typ
         core_resp_offset := byteOffset(creq.addr)
         scheduled_core := true.B
      }

      val core_resp_bank = WireDefault(false.B)
      val dbg_resp_bank = WireDefault(false.B)
      when (dbgwrite || dbgvalid) {
         dbg_resp_bank := bankSel(dbgreq.addr)
      }
      when (cwrite || cvalid) {
         core_resp_bank := bankSel(creq.addr)
      }
      val dbg_dout = Mux(RegNext(dbg_resp_bank, false.B), shared_sram_1.io.dout, shared_sram_0.io.dout)
      val core_dout = Mux(RegNext(core_resp_bank, false.B), shared_sram_1.io.dout, shared_sram_0.io.dout)
      val core_resp_type_q = RegNext(core_resp_type, MT_W)
      val core_resp_offset_q = RegNext(core_resp_offset, 0.U(2.W))
      val dbg_resp_type_q = RegNext(dbgresp_type, MT_W)
      val dbg_resp_offset_q = RegNext(dbgresp_offset, 0.U(2.W))
      io.core_ports(0).resp.valid := RegNext(scheduled_core, false.B)
      io.core_ports(0).resp.bits.data := loadData(core_dout, core_resp_type_q, core_resp_offset_q)
      io.debug_port.resp.valid := RegNext(scheduled_debug, false.B)
      io.debug_port.resp.bits.data := loadData(dbg_dout, dbg_resp_type_q, dbg_resp_offset_q)
   }
}

// SRAM22 macros provide synchronous reads, so all stages use the same backend.
class AsyncScratchPadMemory(num_core_ports: Int, numBytes: Int = 16384)(implicit conf: SodorConfiguration)
  extends Module {
   val io = IO(new ScratchPadIo(num_core_ports))
   if (conf.useAsyncSim) {
      val model = Module(new AsyncScratchPadMemoryModel(num_core_ports, numBytes))
      io <> model.io
   } else {
      val mem = Module(new Sram22ScratchPadBase(num_core_ports, numBytes, "SRAM22-backed")(conf) {})
      io <> mem.io
   }
}

class SyncScratchPadMemory(num_core_ports: Int, numBytes: Int = 16384)(implicit conf: SodorConfiguration)
  extends Module {
   val io = IO(new ScratchPadIo(num_core_ports))
   val mem = Module(new Sram22ScratchPadBase(num_core_ports, numBytes, "SRAM22-backed")(conf) {})
   io <> mem.io
}

}
