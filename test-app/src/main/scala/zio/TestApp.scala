package zio

import java.io._
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

import zio.blocking.Blocking
import zio.clock.Clock
import zio.console2.{Console2, ZBufferedReader}

import scala.annotation.tailrec
import scala.io.StdIn
import scala.util.Try

object NewConsole {
  val old: ZLayer[zio.console.Console, Nothing, Console2] = ZLayer.fromService { console =>
    new Console2.Service {
      override def putStr(msg: String): UIO[Unit] =
        console.putStr(msg)
      override def putStrErr(msg: String): UIO[Unit] =
        console.putStrErr(msg)
      override def putStrLnErr(msg: String): UIO[Unit] =
        console.putStrLnErr(msg)
      override def putStrLn(msg: String): ZIO[Any, Nothing, Unit] =
        console.putStrLn(msg)
      override def getStrLn: ZIO[Any, IOException, String] =
        console.getStrLn
    }
  }

  def unsafeGetStdInStream(): FileInputStream = {
    // We expect System.in to be a FilterInputStream,
    // let's setup access to its `in` field.
    val inField = classOf[FilterInputStream].getDeclaredField("in")
    inField.setAccessible(true)

    var stdin = System.in
    while (stdin.isInstanceOf[FilterInputStream])
      stdin = inField.get(stdin).asInstanceOf[InputStream]
    stdin

    stdin match {
      case fis: FileInputStream => fis
      case _ => sys.error("UNEXPECTED: System.in does not have an underlying FileInputStream")
    }
  }

  def makeServiceFromFileStream(stdin: FileInputStream, blocking: Blocking.Service): ZIO[Any, Nothing, Console2.Service] = {
    for {
      interruptibleStdStream   <- ZIO.effectTotal(unsafeInterruptibleStream(stdin))
      reader <- ZIO.effectTotal(new BufferedReader(new InputStreamReader(interruptibleStdStream)))
    } yield new Console2.Service {
      override def getStrLn: ZIO[Any, IOException, String] =
        blocking.effectBlockingInterrupt {
          val r = reader.readLine()
          if (r == null) throw new EOFException("There is no more input left to read")
          else r
        }.refineToOrDie[IOException]

      override def putStr(msg: String): UIO[Unit] =
        ZIO.effectTotal(System.out.print(msg))
      override def putStrErr(msg: String): UIO[Unit] =
        ZIO.effectTotal(System.err.print(msg))
      override def putStrLnErr(msg: String): UIO[Unit] =
        ZIO.effectTotal(System.err.println(msg))
      override def putStrLn(msg: String): ZIO[Any, Nothing, Unit] =
        ZIO.effectTotal(System.out.println(msg))
    }
  }

  val jruby: ZLayer[Blocking, Nothing, Console2] = ZLayer.fromServiceM { blocking =>
    for {
      stdStream <- UIO(unsafeGetStdInStream())
      service <- makeServiceFromFileStream(stdStream, blocking)
    } yield service
  }

  val fileDescriptor: ZLayer[Blocking, Nothing, Console2] = ZLayer.fromServiceM { blocking =>
    for {
      stdStream <- UIO(new FileInputStream(FileDescriptor.in))
      service <- makeServiceFromFileStream(stdStream, blocking)
    } yield service
  }

  val polling: ZLayer[Blocking, Nothing, Console2] = ZLayer.fromServiceM { blocking =>
    val stdStream = java.lang.System.in

    val interruptibleStdStream = new InputStream {
      var done = false
      override def read(): Int =
        sys.error("Unexpected operation performed by BufferedReader")
      override def available(): Int =
        if (done) 0 else stdStream.available()

      override def read(b: Array[Byte], off: Int, len: Int): Int = {
        @tailrec def go(): Int =
          if (done) 0
          else if (stdStream.available() > 0) {
            val n = stdStream.read(b, off, len)
            if (n == 0) done = true
            n
          } else {
            Thread.sleep(50L)
            go()
          }

        go()
      }
    }

    for {
      reader <- ZIO.effectTotal(new BufferedReader(new InputStreamReader(interruptibleStdStream)))
    } yield new Console2.Service {
      override def getStrLn: ZIO[Any, IOException, String] =
        IO.effect {
          val r = reader.readLine()
          if (r == null) throw new EOFException("There is no more input left to read")
          else r
        }.refineToOrDie[IOException]

      override def putStr(msg: String): UIO[Unit] =
        ZIO.effectTotal(System.out.print(msg))
      override def putStrErr(msg: String): UIO[Unit] =
        ZIO.effectTotal(System.err.print(msg))
      override def putStrLnErr(msg: String): UIO[Unit] =
        ZIO.effectTotal(System.err.println(msg))
      override def putStrLn(msg: String): ZIO[Any, Nothing, Unit] =
        ZIO.effectTotal(System.out.println(msg))
    }
  }

  val polling2: ZLayer[Clock, Nothing, Console2] = ZLayer.fromService { clock =>
    new Console2.Service {
      val reader = scala.Console.in
      val hasClock = Has(clock)

      override val getStrLn: ZIO[Any, IOException, String] = {
        import zio.duration._
        val v: ZIO[Clock, IOException, String] = ZIO
          .sleep(200.millis)
          .repeatUntil(_ => reader.ready()) *> (ZIO
          .fromTry(Try(StdIn.readLine())))
          .refineOrDie[IOException] {
            case e: IOException => e
          }
        v.provide(hasClock)
      }

      override def putStr(msg: String): UIO[Unit] =
        ZIO.effectTotal(System.out.print(msg))
      override def putStrErr(msg: String): UIO[Unit] =
        ZIO.effectTotal(System.err.print(msg))
      override def putStrLnErr(msg: String): UIO[Unit] =
        ZIO.effectTotal(System.err.println(msg))
      override def putStrLn(msg: String): ZIO[Any, Nothing, Unit] =
        ZIO.effectTotal(System.out.println(msg))
    }
  }

  val brokenFast: ZLayer[Any, Nothing, Console2] = ZLayer.succeed {
    val reader = new BufferedReader(new InputStreamReader(System.in))
    val readLine: ZIO[Any, IOException, String] = IO.effect {
      val r = reader.readLine()
      if (r == null) throw new EOFException("eof")
      else r
    }.refineToOrDie[IOException]
    new Console2.Service {
      override def putStr(msg: String): UIO[Unit] =
        ZIO.effectTotal(System.out.print(msg))
      override def putStrErr(msg: String): UIO[Unit] =
        ZIO.effectTotal(System.err.print(msg))
      override def putStrLnErr(msg: String): UIO[Unit] =
        ZIO.effectTotal(System.err.println(msg))

      override def putStrLn(msg: String): ZIO[Any, Nothing, Unit] =
        ZIO.effectTotal(System.out.println(msg))

      override def getStrLn: ZIO[Any, IOException, String] = readLine
    }
  }

  val fast: ZLayer[Blocking, Nothing, Console2] = ZLayer.fromServiceM { blocking =>
    for {
      stdin   <- UIO(new FileInputStream(FileDescriptor.in))
      interruptibleStdStream = unsafeInterruptibleStream(stdin)
      reader0 <- ZIO.effectTotal(new InputStreamReader(interruptibleStdStream))

      bufSize = 8 * 1024
      read = blocking.effectBlockingInterrupt {
        val data = new Array[Char](bufSize)
        val n = reader0.read(data, 0, data.length)
        if (n == -1) throw new EOFException("eof")
        Chunk.fromArray(data.take(n))
      }.refineToOrDie[IOException]

      reader <- ZBufferedReader.make[Any](read)
    } yield new Console2.Service {
      override def putStr(msg: String): UIO[Unit] =
        ZIO.effectTotal(System.out.print(msg))
      override def putStrErr(msg: String): UIO[Unit] =
        ZIO.effectTotal(System.err.print(msg))
      override def putStrLnErr(msg: String): UIO[Unit] =
        ZIO.effectTotal(System.err.println(msg))
      override def putStrLn(msg: String): ZIO[Any, Nothing, Unit] =
        ZIO.effectTotal(System.out.println(msg))
      override def getStrLn: ZIO[Any, IOException, String] =
        reader.readLine
    }
  }

  def putStrLn(line: => String): ZIO[Console2, Nothing, Unit] =
    ZIO.accessM(_.get putStrLn line)

  def putStrLnErr(line: => String): ZIO[Console2, Nothing, Unit] =
    ZIO.accessM(_.get putStrLnErr line)

  def getStrLn: ZIO[Console2, IOException, String] =
    ZIO.accessM(_.get getStrLn)
}

object Main extends zio.App {
  import NewConsole._
  import zio.duration._

  private[this] def pid: String = {
    val name = ManagementFactory.getRuntimeMXBean.getName
    name.split("@") match {
      case Array(first, _) => first
      case _ => name
    }
  }

  override def run(args: List[String]): ZIO[zio.ZEnv, Nothing, ExitCode] =
    for {
      t <- args match {
        case List(m, s) =>
          val layer = m match {
            case "old"            => UIO(NewConsole.old)
            case "jruby"          => UIO(NewConsole.jruby)
            case "fileDescriptor" => UIO(NewConsole.fileDescriptor)
            case "polling"        => UIO(NewConsole.polling)
            case "polling2"       => UIO(NewConsole.polling2)
            case "brokenFast"     => UIO(NewConsole.brokenFast)
            case "fast"           => UIO(NewConsole.fast)
            case "current"        => UIO(Console2.live)
            case _ => ZIO.die(new RuntimeException("Unknown layer."))
          }

          val program = s match {
            case "interrupt" =>
              val a = getStrLn.flatMap(putStrLn(_))
              val b = ZIO.sleep(10.second) *> putStrLn("No input")
              UIO((a race b).as(ExitCode.success))

            case "interrupt-write" =>
              val a = putStrLn("s").repeatN(1000)
              val b = UIO(System.err.println("Huh")) *> ZIO.sleep(5.second) *> UIO(System.err.println("Interrupting!"))
              UIO((a race b).as(ExitCode.success))

            case "pipe" =>
              UIO(for {
                _  <- UIO(System.err.println(s"pid=$pid"))
                t0 <- clock.nanoTime
                r  <- getStrLn.flatMap(line => putStrLn(line)).forever.either
                t1 <- clock.nanoTime
                time = (t1 - t0) / 1000000000.0
                _  <- UIO(System.err.println(s"time=$time"))
                _  <- UIO(System.err.println(s"err=${r.left.get}"))
              } yield ExitCode.success)
            case _ => ZIO.die(new RuntimeException("Unknown scenario."))
          }
          layer <*> program
        case _ => ZIO.die(new RuntimeException("Bad args."))
      }
      (serviceLayer, program) = t

      result <- program.orDie.provideLayer(serviceLayer ++ Clock.any)
    } yield result
}
