import chisel3._ 
import chisel3.util._
import chisel3.util.experimental._
import Constant._
import difftest._




class CSR extends Module {
  val io = IO(new Bundle {
    val pc          = Input(UInt(32.W))
    val pc_timer    = Input(UInt(32.W))
    val inst        = Input(UInt(32.W))
    val csr_type    = Input(UInt(3.W))
    val in_data     = Input(UInt(64.W))
    val time_intrpt = Input(Bool())
    val stall       = Input(Bool())



    val out       = Output(UInt(64.W))
    val jmp       = Output(Bool())
    val jmp_pc    = Output(UInt(32.W))
    val intrpt    = Output(Bool())
    val intrpt_pc = Output(UInt(32.W))
    val intrpt_no = Output(UInt(64.W))
    
    val rd_wen    = Output(Bool())
    
    val mie       = Output(UInt(64.W))
    val mstatus   = Output(UInt(64.W))
    val mepc      = Output(UInt(64.W))
    val mtvec     = Output(UInt(64.W))
    val mcause    = Output(UInt(64.W))
    val mscratch  = Output(UInt(64.W))
    
  })

  val csr_rw = (io.csr_type === CSR_RW) || (io.csr_type === CSR_RS) || (io.csr_type === CSR_RC)
  val csr_jmp = WireInit(Bool(), false.B)
  val csr_ecall = WireInit(Bool(), false.B)
  val csr_mret = WireInit(Bool(), false.B)
  
  val csr_jmp_pc = WireInit(UInt(32.W), 0.U)

  val mhartid   = RegInit(UInt(64.W), 0.U)
  val mstatus   = RegInit(UInt(64.W), "h00001800".U)
  val mie       = RegInit(UInt(64.W), 0.U)
  val mtvec     = RegInit(UInt(64.W), 0.U)
  val mscratch  = RegInit(UInt(64.W), 0.U)
  val mepc      = RegInit(UInt(64.W), 0.U)
  val mcause    = RegInit(UInt(64.W), 0.U)
  val mip       = RegInit(UInt(64.W), 0.U)
  
  val mcycle    = RegInit(UInt(64.W), 0.U)
  val minstret  = RegInit(UInt(64.W), 0.U)
  BoringUtils.addSink(mcycle, "csr_mcycle")
  BoringUtils.addSink(minstret, "csr_minstret")

//io.mie := mie
//io.mstatus := mstatus

//ECALL
  when (io.csr_type === CSR_ECALL) {
    mepc := io.pc
    mcause := 11.U  // Env call from M-mode
    mstatus := Cat(mstatus(63,13), Fill(2, 1.U), mstatus(10,8), mstatus(3), mstatus(6, 4), 0.U, mstatus(2, 0))
    csr_ecall := true.B
    csr_jmp_pc := Cat(mtvec(31, 2), Fill(2, 0.U))
  }

// MRET
  when (io.csr_type === CSR_MRET) {
    mstatus := Cat(mstatus(63,13), Fill(2, 0.U), mstatus(10,8), 1.U, mstatus(6, 4), mstatus(7), mstatus(2, 0))
    csr_mret := true.B
    csr_jmp_pc := mepc(31, 0)
  }

csr_jmp := csr_ecall || csr_mret
// Interrupt


io.intrpt := false.B
io.intrpt_no := 0.U
io.intrpt_pc := 0.U


  when(io.time_intrpt){
        
        mepc := io.pc_timer
        mcause := "h8000000000000007".U  //Machine Timer Interrupt, Only one interrupt is realized
        mstatus := Cat(mstatus(63,13), Fill(2, 1.U), mstatus(10,8), mstatus(3), mstatus(6, 4), 0.U, mstatus(2, 0))
        io.intrpt := true.B
        io.intrpt_no := 7.U
        io.intrpt_pc := Cat(mtvec(31, 2), Fill(2, 0.U))
  
  }
    
  
  //mcycle := mcycle + 1.U

 
  
  
  val addr  = io.inst(31, 20)
  val rdata = WireInit(UInt(64.W), 0.U)
  val wdata = Wire(UInt(64.W))
  val wmask = "hffffffff".U
  val wen   = csr_rw 

  wdata := MuxLookup(io.csr_type, 0.U, Array(
    CSR_RW -> io.in_data,
    CSR_RS -> (rdata | io.in_data ),
    CSR_RC -> (rdata & ~io.in_data)
  ))
  
  rdata := MuxLookup(addr, 0.U, Array(
    csr_addr.mstatus  -> mstatus,
    csr_addr.mcause   -> mcause,
    csr_addr.mie      -> mie,
    csr_addr.mtvec    -> mtvec,
    csr_addr.mscratch -> mscratch,
    csr_addr.mepc     -> mepc,
    csr_addr.mip      -> mip,
    csr_addr.mcycle   -> mcycle,
    csr_addr.minstret -> minstret,
  ))
 
  io.out := rdata

  when(wen && !io.stall) {
    when(addr === csr_addr.mcycle) {
      mcycle := wdata 
    }
    when(addr === csr_addr.mtvec) {
      mtvec := wdata 
    }
    when(addr === csr_addr.mepc) {
      mepc := wdata 
    }
    when(addr === csr_addr.mcause) {
      mcause := wdata 
    }
    when(addr === csr_addr.mstatus) {
      when(io.time_intrpt){mstatus := Cat((wdata(16) & wdata(15)) | (wdata(14) && wdata(13)), wdata(62, 13),1.U,1.U,wdata(10, 0))}
      //当csrw指令写mstatus同时携带计时器中断时，interrupt对mstatus的写操作被覆盖（因为在同一个周期），因此在这里一起写
      .otherwise{mstatus := Cat((wdata(16) & wdata(15)) | (wdata(14) && wdata(13)), wdata(62, 0))}
      
    }
    when(addr === csr_addr.mie) {
      mie := wdata 
    }
    when(addr === csr_addr.mscratch) {
      mscratch := wdata 
    }
  }


  
  BoringUtils.addSource(mie, "csr_mie")
  BoringUtils.addSource(mstatus, "csr_status")

 
  io.rd_wen    := csr_rw
  io.jmp       := csr_jmp
  io.jmp_pc    := csr_jmp_pc
  

  io.mie       := mie
  io.mstatus   := mstatus
  io.mepc      := mepc
  io.mtvec     := mtvec
  io.mcause    := mcause
  io.mscratch  := mscratch

 

}