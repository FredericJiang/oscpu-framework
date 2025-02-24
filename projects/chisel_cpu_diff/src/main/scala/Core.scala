import chisel3._
import chisel3.util._
import chisel3.util.experimental._
import difftest._
import Constant._
import PipelineReg._


class Core extends Module {
  val io = IO(new Bundle {
    val imem = new Core_Inst
    val dmem = new Core_Data
  })


//********************************************************
//Instruction Fetch Stage



stall := (mem_reg_stall || mem_call_stall) && !io.dmem.data_ready
 

when(inst_gen_ready )     { io.imem.inst_req   := true.B  }
.otherwise                 { io.imem.inst_req   := false.B }

when(io.dmem.data_ready) {inst_gen_ready := true.B}

when(io.imem.inst_ready || if_reg_pc === "h7ffffffc".U){
when(stall)              {if_reg_pc := if_reg_pc;        inst_gen_ready:= false.B       }
.elsewhen(reg_kill_flag) {if_reg_pc := reg_exe_pc_nxt;   inst_gen_ready:= true.B; reg_kill_flag := false.B;}
.otherwise               {if_reg_pc := if_reg_pc + 4.U;  inst_gen_ready:= true.B;  }
}

io.imem.inst_addr  := if_reg_pc

val if_from_reg = RegInit(false.B)

when(io.imem.inst_ready && !reg_kill_flag && stall )          {if_reg_inst:= io.imem.inst_read; if_from_reg := true.B }
.elsewhen(io.imem.inst_ready && !reg_kill_flag && !stall )    {if_inst := io.imem.inst_read    } //not read the data as instruction
.otherwise                                                    {if_inst := 0.U}



// Instruction Fetch >>>>>>>> Instruction Decode
//*******************************************************************
when(io.imem.inst_ready && !stall && !reg_kill_flag && !kill_stage){
id_reg_pc    := if_reg_pc
id_reg_inst  :=  io.imem.inst_read
}.elsewhen(stall){
id_reg_pc    := id_reg_pc
id_reg_inst  := id_reg_inst 

}.otherwise{
id_reg_pc    := 0.U
id_reg_inst  := BUBBLE

}

//*******************************************************************
//Decode Instruction Stage

val id_rs1_addr = id_reg_inst(19, 15)
val id_rs2_addr = id_reg_inst(24, 20)


val decode = Module(new Decode)
decode.io.inst      := id_reg_inst

val regfile = Module(new RegFile)
when(decode.io.alu_type =/= ALU_MY_INST){
regfile.io.rs1_addr := id_rs1_addr
}.otherwise{regfile.io.rs1_addr := 10.U}

regfile.io.rs2_addr := id_rs2_addr


val imm_gen = Module(new ImmGen)
imm_gen.io.imm_type := decode.io.imm_type
imm_gen.io.inst     := id_reg_inst

//val id_rs1 = regfile.io.rs1_data



//val jarl_type = (decode.io.op2_type === OP_4) && (decode.io.imm_type === IMM_I)

//used only for jalr

val id_rs1 = MuxCase( regfile.io.rs1_data  , Array(
                  (id_rs1_addr === 0.U && decode.io.op1_type === OP_REG ) -> 0.U ,
                  ((exe_reg_rd_addr === id_rs1_addr) && (id_rs1_addr =/= 0.U) && exe_reg_rd_wen && exe_reg_mem_rtype === MEM_X) -> exe_alu_out,
                  ((mem_reg_rd_addr === id_rs1_addr) && (id_rs1_addr =/= 0.U) && mem_reg_rd_wen) -> Mux(mem_reg_mem_rtype =/= MEM_X, mem_rd_data,mem_reg_alu_out),
                  ((wb_reg_rd_addr  === id_rs1_addr) && (id_rs1_addr =/= 0.U) &&  wb_reg_rd_wen) -> wb_rd_data
                  ))

    

val id_op1  =  MuxCase( regfile.io.rs1_data  , Array(
                  (id_rs1_addr === 0.U && decode.io.op1_type === OP_REG) -> 0.U ,
                  (decode.io.op1_type === OP_IMM ) -> imm_gen.io.imm,
                  (decode.io.op1_type === OP_PC)  -> id_reg_pc,
                  ((exe_reg_rd_addr === id_rs1_addr) && (id_rs1_addr =/= 0.U) && exe_reg_rd_wen && exe_reg_mem_rtype === MEM_X) -> exe_alu_out,
                  ((mem_reg_rd_addr === id_rs1_addr) && (decode.io.op1_type === OP_REG) && (id_rs1_addr =/= 0.U) && mem_reg_rd_wen) -> Mux(mem_reg_mem_rtype =/= MEM_X, mem_rd_data,mem_reg_alu_out),
                  ((wb_reg_rd_addr  === id_rs1_addr) && (decode.io.op1_type === OP_REG) && (id_rs1_addr =/= 0.U) &&  wb_reg_rd_wen) -> wb_rd_data
                  ))

val id_rs2 = MuxCase( regfile.io.rs2_data  , Array(
                  (id_rs1_addr === 0.U && decode.io.op1_type === OP_REG ) -> 0.U ,
                  ((exe_reg_rd_addr === id_rs2_addr) && (id_rs2_addr =/= 0.U) && exe_reg_rd_wen && exe_reg_mem_rtype === MEM_X) -> exe_alu_out,
                  ((mem_reg_rd_addr === id_rs2_addr) && (id_rs2_addr =/= 0.U) && mem_reg_rd_wen) -> Mux(mem_reg_mem_rtype =/= MEM_X, mem_rd_data,mem_reg_alu_out),
                  ((wb_reg_rd_addr  === id_rs2_addr) && (id_rs2_addr =/= 0.U) &&  wb_reg_rd_wen) -> wb_rd_data
                  ))

    
val id_op2 =  MuxCase( regfile.io.rs2_data , Array(
                  (id_rs2_addr === 0.U && decode.io.op2_type === OP_REG) -> 0.U ,
                  (decode.io.op2_type === OP_IMM ) -> imm_gen.io.imm,
                  (decode.io.op2_type === OP_4)    -> 4.U,
                  ((exe_reg_rd_addr === id_rs2_addr) && (id_rs2_addr =/= 0.U) && exe_reg_rd_wen && exe_reg_mem_rtype === MEM_X) -> exe_alu_out,
                  ((mem_reg_rd_addr === id_rs2_addr) && (id_rs2_addr =/= 0.U) && mem_reg_rd_wen) -> Mux(mem_reg_mem_rtype =/= MEM_X, mem_rd_data,mem_reg_alu_out),
                  ((wb_reg_rd_addr  === id_rs2_addr) && (id_rs2_addr =/= 0.U) &&  wb_reg_rd_wen) -> wb_rd_data
                  ))

// load instruciton in exe stage, and address conflict
//generate a bubble
/*
when((exe_reg_mem_rtype =/= MEM_X || exe_reg_alu_type === ALU_COPY2 ) && 
( (exe_reg_rd_addr === id_rs2_addr  && id_rs2_addr=/= 0.U && decode.io.op2_type === OP_REG) || (exe_reg_rd_addr === id_rs1_addr && id_rs1_addr=/= 0.U && decode.io.op1_type === OP_REG)))
{ id_call_stall := true.B }.otherwise{ id_call_stall := false.B }
*/


//Instruction Decode  >>>>>>>>>>>>>>>>>>>   Execute
//*******************************************************************

when(!stall && !kill_stage){
exe_reg_pc        := id_reg_pc
exe_reg_inst      := id_reg_inst

exe_reg_op1_type  := decode.io.op1_type
exe_reg_op2_type  := decode.io.op2_type
exe_reg_alu_type  := decode.io.alu_type
exe_reg_mem_rtype := decode.io.mem_rtype
exe_reg_imm_type  := decode.io.imm_type
exe_reg_wb_type   := decode.io.wb_type
exe_reg_csr_type  := decode.io.csr_type

exe_reg_imm       := imm_gen.io.imm
exe_reg_rs2_data  := id_rs2   //   only used in store struction, and its op2_type is reg, so there is actually no difference with id_op2
exe_reg_rs1_data  := id_rs1
exe_reg_op1_data  := id_op1
exe_reg_op2_data  := id_op2

//when(decode.io.op1_type === OP_REG){exe_reg_rs1_addr  := id_reg_inst(19, 15)}
//.otherwise                         {exe_reg_rs1_addr  := "hffffffffffffffff".U}
//when(decode.io.op2_type === OP_REG){exe_reg_rs2_addr  := id_reg_inst(24, 20)}
//.otherwise                         {exe_reg_rs2_addr  := "hffffffffffffffff".U}
exe_reg_rs1_addr  := id_reg_inst(19, 15)
exe_reg_rs2_addr  := id_reg_inst(24, 20)
exe_reg_rd_addr   := id_reg_inst(11,  7)



exe_reg_rd_wen    := (decode.io.wb_type === WB_REG)
exe_reg_dmem_wen  := (decode.io.wb_type =/= WB_REG) && (decode.io.wb_type =/= WB_X)
exe_reg_dmem_en   := (decode.io.mem_rtype =/= MEM_X) || ((decode.io.wb_type =/= WB_REG) && (decode.io.wb_type =/= WB_X))

}.elsewhen(stall){
exe_reg_pc        := exe_reg_pc
exe_reg_inst      := exe_reg_inst

exe_reg_op1_type  := exe_reg_op1_type 
exe_reg_op2_type  := exe_reg_op2_type 
exe_reg_rs2_data  := exe_reg_rs2_data
exe_reg_rs1_data  := exe_reg_rs1_data
exe_reg_rs1_addr  := exe_reg_rs1_addr
exe_reg_rs2_addr  := exe_reg_rs2_addr
exe_reg_imm       := exe_reg_imm

//exe_reg_csr_type  := 0.U

}
.elsewhen(kill_stage){
exe_reg_pc        := "h0000000000000000".U
exe_reg_inst      := BUBBLE

exe_reg_alu_type  := 0.U
exe_reg_mem_rtype := 0.U
exe_reg_imm_type  := 0.U
exe_reg_wb_type   := 0.U
exe_reg_csr_type  := 0.U

exe_reg_rd_wen    := false.B
exe_reg_dmem_wen  := false.B
exe_reg_dmem_en   := false.B

}
/*.elsewhen(id_call_stall){
exe_reg_pc        := exe_reg_pc
exe_reg_inst      := exe_reg_inst

}
*/

//*******************************************************************
// Execute Stage

val exe_op1     = Wire(UInt(64.W))
val exe_op2     = Wire(UInt(64.W))

//still have bugs here   in cpu-tests/recursion.bin
when((exe_reg_rs1_addr === wb_reg_rd_addr && exe_reg_rs1_addr =/= mem_reg_rd_addr && exe_reg_rs1_addr =/= 0.U ) 
&& wb_reg_rd_wen && wb_reg_wb_type === WB_REG && exe_reg_op1_type === OP_REG)  
                                 {exe_op1 := wb_rd_data  }  
.elsewhen((exe_reg_rs1_addr === mem_reg_rd_addr)&& exe_reg_rs1_addr =/= 0.U && (mem_reg_rd_wen || mem_reg_stall_wen) && exe_reg_op1_type === OP_REG && mem_reg_alu_type =/= ALU_COPY2)   
{
when(mem_reg_mem_rtype =/= MEM_X){exe_op1 := mem_rd_data}
.otherwise                       {exe_op1 := mem_reg_alu_out}
  }
.otherwise                       {exe_op1 := exe_reg_op1_data}


when((exe_reg_rs2_addr === wb_reg_rd_addr && exe_reg_rs2_addr =/= mem_reg_rd_addr  && exe_reg_rs2_addr =/= 0.U ) 
&& wb_reg_rd_wen && wb_reg_wb_type === WB_REG && exe_reg_op2_type === OP_REG)  
                                 {exe_op2 := wb_rd_data }
.elsewhen((exe_reg_rs2_addr === mem_reg_rd_addr )&& exe_reg_rs2_addr =/= 0.U  && (mem_reg_rd_wen || mem_reg_stall_wen) && exe_reg_op2_type === OP_REG && mem_reg_alu_type =/= ALU_COPY2)   
{
when(mem_reg_mem_rtype =/= MEM_X){exe_op2 := mem_rd_data}
.otherwise                       {exe_op2 := mem_reg_alu_out}
  }
.otherwise                       {exe_op2 := exe_reg_op2_data }


when(exe_reg_alu_type === ALU_MY_INST && wb_reg_rd_addr === 10.U && wb_reg_rd_wen ){exe_reg_print := wb_rd_data}
.elsewhen(exe_reg_alu_type === ALU_MY_INST) {exe_reg_print := exe_reg_rs1_data}



//exe_op1 := exe_reg_op1_data
//exe_op2 := exe_reg_op2_data 

val alu = Module(new ALU)
alu.io.inst     := exe_reg_inst
alu.io.alu_type := exe_reg_alu_type
alu.io.in1      := exe_op1
alu.io.in2      := exe_op2
exe_alu_out     := alu.io.alu_out

val clint_en  = Wire(Bool())

when(exe_reg_dmem_en && 
(exe_alu_out === CLINT_MTIME || exe_alu_out === CLINT_MTIMECMP))
           {clint_en := true.B}
.otherwise {clint_en := false.B}

val exe_stage_valid = WireInit(false.B)

when(exe_reg_pc =/= 0.U){
  exe_stage_valid := true.B
}


val clint = Module(new Clint)
clint.io.cmp_ren    :=  (exe_reg_mem_rtype =/= MEM_X) && clint_en
clint.io.cmp_wen    :=  exe_reg_dmem_wen && clint_en
clint.io.cmp_addr   :=  exe_alu_out
clint.io.cmp_wdata  :=  exe_reg_rs2_data
clint.io.time_valid :=  exe_stage_valid

val csr  = Module(new CSR)
csr.io.stall       := stall
csr.io.pc          := exe_reg_pc
csr.io.pc_timer    := exe_reg_pc
csr.io.inst        := exe_reg_inst
csr.io.in_data     := exe_alu_out
csr.io.csr_type    := exe_reg_csr_type
csr.io.time_intrpt := (clint.io.time_intrpt && exe_reg_inst =/= BUBBLE)


val nxt_pc = Module(new Nxt_PC)
nxt_pc.io.pc          := exe_reg_pc
nxt_pc.io.imm_type    := exe_reg_imm_type
nxt_pc.io.alu_type    := exe_reg_alu_type
nxt_pc.io.imm         := exe_reg_imm
nxt_pc.io.alu_out     := exe_alu_out
nxt_pc.io.op2_type    := exe_reg_op2_type
nxt_pc.io.csr_jmp     := csr.io.jmp 
nxt_pc.io.csr_jmp_pc  := csr.io.jmp_pc

nxt_pc.io.intrpt_jmp     := csr.io.intrpt
nxt_pc.io.intrpt_jmp_pc  := csr.io.intrpt_pc

// a ld instruction before jalr 
//only in mem stage can gain the jmp address

when(exe_reg_rs1_addr === mem_reg_rd_addr && mem_reg_mem_rtype =/= MEM_X)
{nxt_pc.io.rs1_data := mem_rd_data}

.elsewhen((exe_reg_rs1_addr === wb_reg_rd_addr && exe_reg_rs1_addr =/= mem_reg_rd_addr ) 
&& wb_reg_rd_wen && (exe_reg_op2_type === OP_4 && exe_reg_imm_type === IMM_I)) //11111111111
{nxt_pc.io.rs1_data := wb_rd_data}

.elsewhen(exe_reg_op2_type === OP_4 && exe_reg_imm_type === IMM_I) {nxt_pc.io.rs1_data := exe_reg_rs1_data}
.otherwise(nxt_pc.io.rs1_data := 0.U)



exe_pc_nxt  := nxt_pc.io.pc_nxt
kill_stage  := nxt_pc.io.pc_jmp  //current instruction jmp_flag

//val exe_reg_alu_out
//when(stall)     {exe_reg_alu_out:= exe_alu_out}

when(kill_stage){ reg_exe_pc_nxt:= nxt_pc.io.pc_nxt; reg_kill_flag := nxt_pc.io.pc_jmp }

//Execute  >>>>>>>>>>>>>>>>>>>>> Memory
//*******************************************************************

// timer inrerupt stop read and write to ram and register
when(!csr.io.intrpt){

mem_reg_rd_wen      := exe_reg_rd_wen 
mem_reg_dmem_wen    := exe_reg_dmem_wen && !clint_en
mem_reg_dmem_en     := exe_reg_dmem_en  && !clint_en

}.otherwise{

mem_reg_rd_wen      := false.B
mem_reg_dmem_wen    := false.B
mem_reg_dmem_en     := false.B

}
when(!stall)
//when(!mem_call_stall && !mem_reg_stall) //非stall时接受exe级数据，否则默认保持
{
mem_reg_pc          := exe_reg_pc
mem_reg_inst        := exe_reg_inst

// Control Signal
mem_reg_alu_type    := exe_reg_alu_type
mem_reg_mem_rtype   := exe_reg_mem_rtype
mem_reg_wb_type     := exe_reg_wb_type
mem_reg_csr_type    := exe_reg_csr_type
mem_reg_alu_out     := exe_alu_out


// Data Signal
mem_reg_rs1_addr    := exe_reg_rs1_addr
mem_reg_rs2_addr    := exe_reg_rs2_addr
mem_reg_rd_addr     := exe_reg_rd_addr
mem_reg_rs1_data    := exe_reg_rs1_data
mem_reg_print       := exe_reg_print

when((exe_reg_rs2_addr === wb_reg_rd_addr && exe_reg_rs2_addr =/= mem_reg_rd_addr ) 
&& wb_reg_rd_wen && exe_reg_wb_type >1.U){mem_reg_rs2_data := wb_rd_data}
.otherwise{mem_reg_rs2_data    := exe_reg_rs2_data}


//*******************************************************************
// MEM CSR REG
mem_reg_mie         := csr.io.mie
mem_reg_mstatus     := csr.io.mstatus
mem_reg_mepc        := csr.io.mepc
mem_reg_mcause      := csr.io.mcause
mem_reg_mtvec       := csr.io.mtvec
mem_reg_mscratch    := csr.io.mscratch
mem_reg_intrpt      := csr.io.intrpt  
mem_reg_intrpt_no   := csr.io.intrpt_no
mem_reg_clint_en    := clint_en
mem_reg_csr_rd_wen  := csr.io.rd_wen
mem_reg_csr_rd_data := csr.io.out 
}


//*******************************************************************
//MEMORY Stage
val lsu = Module(new LSU)
val mem_dmem_addr  = Wire(UInt(64.W))
val mem_reg_dmem_addr  = Reg(UInt(64.W))

// read & write memory address is from ALU
when(mem_reg_dmem_en){ mem_dmem_addr := mem_reg_alu_out; mem_reg_dmem_addr:= mem_reg_alu_out }    
.otherwise           { mem_dmem_addr := 0.U;  }

// Core to AXI for DATA_MEM

io.dmem.data_req_r  := exe_reg_dmem_en  && !clint_en && !exe_reg_dmem_wen
io.dmem.data_req_w  := exe_reg_dmem_wen && !clint_en
io.dmem.data_strb   := lsu.io.dmem_wmask
io.dmem.data_write  := lsu.io.dmem_wdata

// data_read 地址只给一拍
io.dmem.data_addr_r  := mem_dmem_addr

//data_wrtie 地址一直给
when(mem_reg_dmem_wen)
{io.dmem.data_addr_w  := mem_dmem_addr}
.otherwise{io.dmem.data_addr_w := mem_reg_dmem_addr}



when(io.dmem.data_ready){lsu.io.dmem_rdata  := io.dmem.data_read}
.otherwise              {lsu.io.dmem_rdata  := 0.U}

lsu.io.mem_rtype  := mem_reg_mem_rtype
lsu.io.wb_type    := mem_reg_wb_type

when(mem_reg_dmem_wen){
  lsu.io.dmem_addr  := mem_dmem_addr
}.otherwise{
lsu.io.dmem_addr  := mem_reg_dmem_addr
}

when((mem_reg_rs2_addr === wb_reg_rd_addr) 
&&(mem_reg_dmem_wen && wb_reg_rd_wen)) { lsu.io.rs2_data  := wb_rd_data       }
.otherwise                             { lsu.io.rs2_data  := mem_reg_rs2_data } //write memory data is from rs2

// LD instruction Data Path
mem_rd_data   := lsu.io.mem_rdata

mem_reg_rd_data:= mem_rd_data




when(mem_reg_dmem_en && !io.dmem.data_ready) { mem_reg_stall := true.B  ;  mem_call_stall:= true.B 

mem_reg_stall_wen:= mem_reg_rd_wen //when fetch data from AXI and reserve wen for wb stage

}
.elsewhen(io.dmem.data_ready)                { mem_reg_stall := false.B ;  exe_stop_stall:= true.B}

when(!mem_reg_stall && !mem_call_stall)      {mem_reg_stall_wen:= false.B}
// Memmory >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> Write Back
//*******************************************************************
// signals for difftest
//when(!mem_reg_stall && !mem_call_stall)
when(!stall)
{

wb_reg_pc          := mem_reg_pc
wb_reg_inst        := mem_reg_inst

wb_reg_alu_type    := mem_reg_alu_type
wb_reg_mem_rtype   := mem_reg_mem_rtype 
wb_reg_csr_type    := mem_reg_csr_type
wb_reg_wb_type     := mem_reg_wb_type

wb_reg_alu_out     := mem_reg_alu_out
wb_reg_rs1_data    := mem_reg_rs1_data //used for print
wb_reg_print       := mem_reg_print

wb_reg_rd_addr     := mem_reg_rd_addr
wb_reg_rs2_addr    := mem_reg_rs2_addr //
wb_reg_rd_wen      := mem_reg_rd_wen || mem_reg_stall_wen
wb_reg_rd_data     := mem_rd_data
wb_reg_csr_rd_data := mem_reg_csr_rd_data


// For difftest

//wb_reg_dmem_wen    := mem_reg_dmem_wen
//wb_reg_wdata       := lsu.io.dmem_wdata
//wb_reg_wdest       := mem_dmem_addr

//*******************************************************************
// WB CSR REG
wb_reg_intrpt      :=  mem_reg_intrpt
wb_reg_intrpt_no   :=  mem_reg_intrpt_no
wb_reg_csr_rd_wen  :=  mem_reg_csr_rd_wen
wb_reg_clint_en    :=  mem_reg_clint_en
wb_reg_mie         :=  mem_reg_mie
wb_reg_mtvec       :=  mem_reg_mtvec
wb_reg_mscratch    :=  mem_reg_mscratch

wb_reg_mstatus  :=  mem_reg_mstatus
wb_reg_mepc     :=  mem_reg_mepc
wb_reg_mcause   :=  mem_reg_mcause
}.otherwise{
  
wb_reg_pc:= 0.U
wb_reg_inst := 0.U
wb_reg_wb_type:=0.U

wb_reg_intrpt      :=  mem_reg_intrpt
wb_reg_intrpt_no   :=  mem_reg_intrpt_no
wb_reg_csr_rd_wen  :=  mem_reg_csr_rd_wen
wb_reg_clint_en    :=  mem_reg_clint_en
wb_reg_mie         :=  mem_reg_mie
wb_reg_mtvec       :=  mem_reg_mtvec
wb_reg_mscratch    :=  mem_reg_mscratch

wb_reg_mstatus  :=  mem_reg_mstatus
wb_reg_mepc     :=  mem_reg_mepc
wb_reg_mcause   :=  mem_reg_mcause
}


//*******************************************************************
//WriteBack
//write back to reg enalbe
regfile.io.rd_wen   := (wb_reg_rd_wen && wb_reg_wb_type === WB_REG)|| wb_reg_csr_rd_wen
regfile.io.rd_addr  := wb_reg_rd_addr


wb_rd_data  := MuxCase(0.U, Array(
                  (wb_reg_csr_rd_wen) -> wb_reg_csr_rd_data,
                  (wb_reg_mem_rtype === MEM_X && !wb_reg_csr_rd_wen) -> wb_reg_alu_out,
                  (wb_reg_mem_rtype =/= MEM_X && !wb_reg_csr_rd_wen) -> wb_reg_rd_data
                  
                  ))

regfile.io.rd_data := wb_rd_data




// MY_INST print output

val my_inst = RegInit(0.U(1.W))

when(wb_reg_alu_type === ALU_MY_INST)
{ val a =  WireInit(0.U(64.W))
  a:= wb_reg_print
printf("%c", a) }





















/* ------------------ Difftest ------------------ */

val dt_valid = RegInit(false.B)

val skip = RegInit(false.B)

when((wb_reg_alu_type === ALU_MY_INST) || (wb_reg_csr_type =/= CSR_X && wb_reg_inst(31,20) === csr_addr.mcycle) || wb_reg_clint_en  )
{
  skip := true.B
}.otherwise{
  skip:= false.B
}


dt_valid := (wb_reg_inst =/= BUBBLE && wb_reg_pc =/= "hffffffffffffffff".U && !wb_reg_intrpt)

when(dt_valid ){
val dt_ic = Module(new DifftestInstrCommit)
  dt_ic.io.pc       := RegNext(wb_reg_pc)
  dt_ic.io.instr    := RegNext(wb_reg_inst)
  dt_ic.io.clock    := clock
  dt_ic.io.coreid   := 0.U
  dt_ic.io.index    := 0.U
  dt_ic.io.valid    := dt_valid
  dt_ic.io.special  := 0.U
  dt_ic.io.skip     := skip
  dt_ic.io.isRVC    := false.B
  dt_ic.io.scFailed := false.B
  dt_ic.io.wen      := RegNext(wb_reg_rd_wen || wb_reg_csr_rd_wen)
  dt_ic.io.wdata    := RegNext(wb_rd_data)
  dt_ic.io.wdest    := RegNext(wb_reg_rd_addr)




  val cycle_cnt = RegInit(0.U(64.W))
  val instr_cnt = RegInit(0.U(64.W))

  when(dt_ic.io.valid ){ instr_cnt := instr_cnt + 1.U }
  cycle_cnt := cycle_cnt + 1.U

  BoringUtils.addSource(cycle_cnt, "csr_mcycle")
  BoringUtils.addSource(instr_cnt, "csr_minstret")

  val rf_a0 = WireInit(0.U(64.W))
  BoringUtils.addSink(rf_a0, "rf_a0")



  val dt_te = Module(new DifftestTrapEvent)
  dt_te.io.clock    := clock
  dt_te.io.coreid   := 0.U
  dt_te.io.valid    := (wb_reg_inst === "h0000006b".U)
  dt_te.io.code     := rf_a0(2, 0)
  dt_te.io.pc       := wb_reg_pc
  dt_te.io.cycleCnt := cycle_cnt
  dt_te.io.instrCnt := instr_cnt




  val dt_ae = Module(new DifftestArchEvent)
    dt_ae.io.clock        := clock
    dt_ae.io.coreid       := 0.U
    dt_ae.io.intrNO       := RegNext(Mux(wb_reg_intrpt, wb_reg_intrpt_no, 0.U))
    dt_ae.io.cause        := 0.U
    dt_ae.io.exceptionPC  := RegNext(Mux(wb_reg_intrpt, wb_reg_pc, 0.U))



  val dt_cs = Module(new DifftestCSRState)
    dt_cs.io.clock          := clock
    dt_cs.io.coreid         := 0.U
    dt_cs.io.priviledgeMode := 3.U  // Machine mode
    dt_cs.io.mstatus        := wb_reg_mstatus
    dt_cs.io.sstatus        := wb_reg_mstatus & "h80000003000de122".U
    dt_cs.io.mepc           := wb_reg_mepc
    dt_cs.io.sepc           := 0.U
    dt_cs.io.mtval          := 0.U
    dt_cs.io.stval          := 0.U
    dt_cs.io.mtvec          := wb_reg_mtvec
    dt_cs.io.stvec          := 0.U
    dt_cs.io.mcause         := wb_reg_mcause
    dt_cs.io.scause         := 0.U
    dt_cs.io.satp           := 0.U
    dt_cs.io.mip            := 0.U
    dt_cs.io.mie            := wb_reg_mie
    dt_cs.io.mscratch       := wb_reg_mscratch
    dt_cs.io.sscratch       := 0.U
    dt_cs.io.mideleg        := 0.U
    dt_cs.io.medeleg        := 0.U
  



}




//printf("pc in core =%x, inst in core =%x",if_reg_pc,if_reg_inst)
//printf("dt_ic_valid =%x, dt_pc =%x, dt_inst =%x  \n",dt_ic.io.valid ,dt_ic.io.pc ,dt_ic.io.instr)
//printf("dt_ic.io.wen =%x, dt_ic.io.wdata =%x, dt_ic.io.wdest =%x \n",dt_ic.io.wen ,dt_ic.io.wdata ,dt_ic.io.wdest)
}
