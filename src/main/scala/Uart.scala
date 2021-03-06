import Chisel._

class UartTx(val wtime: Int) extends Module {
  val io = new Bundle {
    val txd = Bool(OUTPUT)
    val enq = Decoupled(UInt(width = 8)).flip
  }
  val time = UInt(wtime, log2Up(wtime))
  val idle :: runnings  = Enum(UInt(), 11)

  val state = Reg(init = idle)
  val count = Reg(init = time)
  val buf   = Reg(init = UInt("b111111111"))

  io.txd := buf(0)

  switch (state) {
    is(idle) {
      when (io.enq.valid) {
        buf := io.enq.bits ## UInt("b0")
        count := time
        state := runnings.last
      }
    }
    is(runnings) {
      when (count === UInt(0)) {
        buf := UInt("b1") ## buf(8, 1)
        count := time
        state := state - UInt(1)
      } .otherwise {
        count := count - UInt(1)
      }
    }
  }

  io.enq.ready := (state === idle)
}

class UartRx(val wtime: Int) extends Module {
  val io = new Bundle {
    val rxd = Bool(INPUT)
    val deq = Decoupled(UInt(width = 8))
  }
  val time = UInt(wtime, log2Up(wtime))
  val time_h = UInt(wtime / 2, log2Up(wtime)) // half period
  val idle :: stop :: runnings = Enum(UInt(), 11)

  val state = Reg(init = idle)
  val count = Reg(init = time_h)
  val buf   = Reg(init = UInt("b000000000"))
  val valid = Reg(init = Bool(false))

  when (valid && io.deq.ready) {
    valid := Bool(false)
  }

  switch (state) {
    is(idle) {
      when (io.rxd === UInt(0)) {
        when (count != UInt(0)) {
          count := count - UInt(1)
        } .otherwise {
          count := time
          state := runnings.last
          valid := Bool(false)
        }
      }
    }
    is(runnings) {
      when (count === UInt(0)) {
        buf := io.rxd ## buf(8, 1)
        count := time
        state := state - UInt(1)
      } .otherwise {
        count := count - UInt(1)
      }
    }
    is(stop) {
      when (count === time_h) {
        count := count - UInt(1)
        state := idle
        valid := Bool(true)
      } .otherwise {
        count := count - UInt(1)
      }
    }
  }

  io.deq.valid := valid
  io.deq.bits := buf(7, 0)
}

class Uart(val wtime: Int) extends Module {
  val io = new Bundle {
    val txd = Bool(OUTPUT)
    val rxd = Bool(INPUT)
    val enq = Decoupled(UInt(width = 8)).flip
    val deq = Decoupled(UInt(width = 8))
  }
  val tx = Module(new UartTx(wtime))
  val rx = Module(new UartRx(wtime))

  tx.io.txd <> io.txd
  tx.io.enq <> io.enq
  rx.io.rxd <> io.rxd
  rx.io.deq <> io.deq
}

class BufferedUartTx(val wtime: Int, val entries: Int) extends Module {
  val io = new Bundle {
    val txd = Bool(OUTPUT)
    val enq = Decoupled(UInt(width = 8)).flip
    val count = UInt(OUTPUT, log2Up(entries + 1))
  }
  val queue = Module(new Queue(UInt(width = 8), entries))
  val tx = Module(new UartTx(wtime))

  queue.io.enq <> io.enq
  tx.io.enq <> queue.io.deq
  io.txd <> tx.io.txd
  io.count <> queue.io.count
}

class BufferedUartRx(val wtime: Int, val entries: Int) extends Module {
  val io = new Bundle {
    val rxd = Bool(INPUT)
    val deq = Decoupled(UInt(width = 8))
    val count = UInt(OUTPUT, log2Up(entries + 1))
  }
  val queue = Module(new Queue(UInt(width = 8), entries))
  val rx = Module(new UartRx(wtime))

  queue.io.enq <> rx.io.deq
  io.deq <> queue.io.deq
  io.rxd <> rx.io.rxd
  io.count <> queue.io.count
}

class BufferedUart(val wtime: Int, val entries: Int) extends Module {
  val io = new Bundle {
    val txd = Bool(OUTPUT)
    val rxd = Bool(INPUT)
    val enq = Decoupled(UInt(width = 8)).flip
    val deq = Decoupled(UInt(width = 8))
  }
  val tx = Module(new BufferedUartTx(wtime, entries))
  val rx = Module(new BufferedUartRx(wtime, entries))

  tx.io.txd <> io.txd
  tx.io.enq <> io.enq
  rx.io.rxd <> io.rxd
  rx.io.deq <> io.deq
}

object Uart {

  val wtime = 0x1458;
  val entries = 128;

  def main(args: Array[String]): Unit = {
    chiselMainTest(args, () => Module(new UartLoopback)) { c =>
      new UartLoopbackTests(c)
    }
    chiselMainTest(args, () => Module(new UartBufferedLoopback)) { c =>
      new UartBufferedLoopbackTests(c)
    }
    chiselMainTest(args, () => Module(new BufferedUart(wtime, entries))) { c =>
      new NullTest(c)
    }
  }

  class NullTest (c: BufferedUart) extends Tester(c) {
    ;
  }

  class UartLoopback extends Module {
    val io = new Bundle {
      val tx = Decoupled(UInt(width = 8)).flip
      val rx = Decoupled(UInt(width = 8))
    }
    val uart = Module(new Uart(0x1ADB))

    uart.io.rxd := uart.io.txd

    io.tx <> uart.io.enq
    io.rx <> uart.io.deq
  }

  class UartLoopbackTests(c: UartLoopback) extends Tester(c, isTrace = false) {
    poke(c.io.tx.valid, 0)

    step(10)

    for (value <- "Hello") {
      while (peek(c.io.tx.ready) == 0) {
        step(1)
      }
      poke(c.io.tx.valid, 1)
      poke(c.io.tx.bits, value.intValue())

      step(1)

      poke(c.io.tx.valid, 0)

      while (peek(c.io.rx.valid) == 0) {
        step(1)
      }
      poke(c.io.rx.ready, 1)
      expect(c.io.rx.bits, value.intValue())

      println("expect: " + value.intValue() + ", and got: " + peek(c.io.rx.bits))

      step(1)

      poke(c.io.rx.ready, 0)
    }
  }

  class UartBufferedLoopback extends Module {
    val io = new Bundle {
      val tx = Decoupled(UInt(width = 8)).flip
      val rx = Decoupled(UInt(width = 8))
    }
    val uart = Module(new BufferedUart(0x1ADB, 16))

    uart.io.rxd := uart.io.txd

    io.tx <> uart.io.enq
    io.rx <> uart.io.deq
  }

  class UartBufferedLoopbackTests(c: UartBufferedLoopback) extends Tester(c, isTrace = false) {

    poke(c.io.tx.valid, 0)
    poke(c.io.rx.ready, 0)

    step(1)

    def send(values : Seq[Int]) {
      for (value <- values) {
        while (peek(c.io.tx.ready) == 0) {
          step(1)
        }

        poke(c.io.tx.valid, 1)
        poke(c.io.tx.bits, value)

        println("sent: " + value)

        step(1)

        poke(c.io.tx.valid, 0)
      }
    }

    def recv(values : Seq[Int]) {
      poke(c.io.rx.ready, 1)

      for (value <- values) {
        while (peek(c.io.rx.valid) == 0) {
          step(1)
        }

        expect(c.io.rx.bits, value)

        println("recv: " + peek(c.io.rx.bits))

        step(1)
      }

      poke(c.io.rx.ready, 1)

      step(1)
    }

    send(List[Int](0x12, 0x34, 0x56, 0x78, 0x90))
    recv(List[Int](0x12, 0x34, 0x56, 0x78, 0x90))
  }

}
