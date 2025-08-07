base_dir=$(abspath ./)

CHISEL_VERSION=3.6.0
MODEL ?= TestHarness
PROJECT ?= freechips.rocketchip.system
CFG_PROJECT ?= $(PROJECT)
CONFIG ?= $(CFG_PROJECT).DefaultSmallConfig
MILL ?= mill

VSRC ?= src/main/resources/vsrc
VERILOG_DIR ?= out/emulator/freechips.rocketchip.system.TestHarness/$(CONFIG)/mfccompiler/compile.dest
VERILOG_FILE ?= $(VERILOG_DIR)/*.sv $(VERILOG_DIR)/*.v $(VSRC)/EICG_wrapper.v
TOP_MODULE ?= TestHarness
BTOR_OUTPUT_FILE ?= $(TOP_MODULE).btor

SCRIPT_FILE = $(TOP_MODULE).ys

.PHONY: clean $(SCRIPT_FILE) 

btor: $(BTOR_OUTPUT_FILE)
	
$(SCRIPT_FILE):
	@echo "Generating yosys script: $@"
	@echo "read_verilog -sv $(VERILOG_FILE)" > $@
	@echo "hierarchy -top $(TOP_MODULE)" >> $@
	@echo "hierarchy -check" >> $@
	@echo "proc" >> $@
	@echo "opt" >> $@
	@echo "prep -top $(TOP_MODULE)" >> $@
	@echo "flatten" >> $@
	@echo "memory -nordff" >> $@
	@echo "async2sync" >> $@
	@echo "clk2fflogic" >> $@
	@echo "setundef -undriven -init -zero" >> $@
	@echo "write_btor -s $(BTOR_OUTPUT_FILE)" >> $@ 


$(BTOR_OUTPUT_FILE): $(VERILOG_FILE) $(SCRIPT_FILE)
	@yosys -s $(SCRIPT_FILE)
	@echo "Conversion complete: $@"


verilog:
	cd $(base_dir) && $(MILL) emulator[freechips.rocketchip.system.TestHarness,$(CONFIG)].mfccompiler.compile

verilator:
	cd $(base_dir) && $(MILL) emulator[freechips.rocketchip.system.TestHarness,$(CONFIG)].elf

clean:
	rm -rf out/ $(SCRIPT_FILE) $(BTOR_OUTPUT_FILE)