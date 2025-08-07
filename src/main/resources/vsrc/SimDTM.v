// See LICENSE.SiFive for license details.
//VCS coverage exclude_file

module SimDTM(
  input clk,
  input reset,

  output        debug_req_valid,
  input         debug_req_ready,
  output [ 6:0] debug_req_bits_addr,
  output [ 1:0] debug_req_bits_op,
  output [31:0] debug_req_bits_data,

  input         debug_resp_valid,
  output        debug_resp_ready,
  input  [ 1:0] debug_resp_bits_resp,
  input  [31:0] debug_resp_bits_data,

  output [31:0] exit
);

  wire __debug_req_ready = debug_req_ready;
  wire __debug_resp_valid = debug_resp_valid;
  wire [31:0] __debug_resp_bits_resp = {30'b0, debug_resp_bits_resp};
  wire [31:0] __debug_resp_bits_data = debug_resp_bits_data;

  reg        debug_req_valid_reg;
  reg [ 6:0] debug_req_bits_addr_reg;
  reg [ 1:0] debug_req_bits_op_reg;
  reg [31:0] debug_req_bits_data_reg;
  reg        debug_resp_ready_reg;
  reg [31:0] exit_reg;

  assign exit = exit_reg;

endmodule
