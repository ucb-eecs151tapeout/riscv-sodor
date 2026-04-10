(* black_box *)
module sram22_2048x32m8w8(
`ifdef USE_POWER_PINS
  vdd,
  vss,
`endif
  clk,rstb,ce,we,wmask,addr,din,dout
);
`ifdef USE_POWER_PINS
  inout vdd;
  inout vss;
`endif
  input        clk;
  input        rstb;
  input        ce;
  input        we;
  input  [3:0] wmask;
  input [10:0] addr;
  input [31:0] din;
  output [31:0] dout;
endmodule
