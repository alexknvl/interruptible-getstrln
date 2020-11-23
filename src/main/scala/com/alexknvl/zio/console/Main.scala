package com.alexknvl.zio.console

import java.io.{BufferedReader, EOFException, FileDescriptor, FileInputStream, FilterInputStream, IOException, InputStream, InputStreamReader}
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedDeque

import zio.blocking.Blocking
import zio.clock.Clock
import zio.stream.ZStream
import zio.{Chunk, ExitCode, Has, IO, Schedule, UIO, ZIO, ZLayer, blocking, clock}

import scala.annotation.tailrec

object `package` {
  type NewConsole = Has[NewConsole.Service]
}

object NewConsole {
  trait Service {
    def putStrLn(msg: String): ZIO[Any, Nothing, Unit]
    def getStrLn: ZIO[Any, IOException, String]
  }

  sealed abstract class GetStdInError(msg: String, cause: Throwable = null)
    extends Throwable(msg, cause) with Product with Serializable
  object GetStdInError {
    final case class ReflectionFailed(cause: Throwable)
      extends GetStdInError("Could not access FilterInputStream's `in` field.")
    final case class UnderlyingStreamIsNotFile()
      extends GetStdInError("System.in does not have an underlying FileInputStream.")
  }
  def getStdInStream: ZIO[Any, Throwable, FileInputStream] = {
    for {
      inField <- ZIO.effect {
        // We expect System.in to be a FilterInputStream,
        // let's setup access to its `in` field.
        val inField = classOf[FilterInputStream].getDeclaredField("in")
        inField.setAccessible(true)
        inField
      }.refineOrDie {
        case e@(_: NoSuchFieldException | _: SecurityException) =>
          GetStdInError.ReflectionFailed(e)
      }

      stdin <- ZIO.effect {
        // Normally the underlying FileInputStream is directly
        // in the "in" field but here we make sure it's not hidden
        // in yet another inner `FilterInputStream`.
        var stdin = System.in
        while (stdin.isInstanceOf[FilterInputStream])
          stdin = inField.get(stdin).asInstanceOf[InputStream]
        stdin
      }.refineOrDie {
        case e: IllegalAccessException =>
          GetStdInError.ReflectionFailed(e)
      }

      // The resulting input stream should have type FileInputStream.
      fis <- stdin match {
        case fis: FileInputStream => UIO(fis)
        case _ =>
          // UNEXPECTED: System.in does not have an underlying FileInputStream.
          ZIO.fail(GetStdInError.UnderlyingStreamIsNotFile())
      }
    } yield fis
  }

  def makeServiceFromFileStream(stdin: FileInputStream, blocking: Blocking.Service): ZIO[Any, Nothing, NewConsole.Service] = {
    for {
      channel   <- ZIO.effectTotal(stdin.getChannel)
      interruptibleStdStream = new InputStream {
        override def read(): Int =
          sys.error("Unexpected operation performed by BufferedReader")
        override def available(): Int =
          stdin.available()

        override def read(b: Array[Byte], off: Int, len: Int): Int = {
          val data = ByteBuffer.allocate(len)
          val n = channel.read(data)
          if (n >= 0) {
            data.position(0)
            data.get(b, off, n)
            n
          } else n
        }
      }
      reader <- ZIO.effectTotal(new BufferedReader(new InputStreamReader(interruptibleStdStream)))
    } yield new Service {
      override def getStrLn: ZIO[Any, IOException, String] =
        blocking.effectBlockingInterrupt {
          val r = reader.readLine()
          if (r == null) throw new EOFException("There is no more input left to read")
          else r
        }.refineToOrDie[IOException]

      override def putStrLn(msg: String): ZIO[Any, Nothing, Unit] =
        ZIO.effectTotal(System.out.println(msg))
    }
  }

  val old: ZLayer[zio.console.Console, Nothing, NewConsole] = ZLayer.fromService { console =>
    new Service {
      override def putStrLn(msg: String): ZIO[Any, Nothing, Unit] = console.putStrLn(msg)
      override def getStrLn: ZIO[Any, IOException, String] = console.getStrLn
    }
  }

  val jruby: ZLayer[Blocking, Nothing, NewConsole] = ZLayer.fromServiceM { blocking =>
    for {
      stdStream <- getStdInStream.orDie
      service <- makeServiceFromFileStream(stdStream, blocking)
    } yield service
  }

  val fileDescriptor: ZLayer[Blocking, Nothing, NewConsole] = ZLayer.fromServiceM { blocking =>
    for {
      stdStream <- ZIO.effectTotal(new FileInputStream(FileDescriptor.in))
      service <- makeServiceFromFileStream(stdStream, blocking)
    } yield service
  }

  val polling: ZLayer[Blocking, Nothing, NewConsole] = ZLayer.fromServiceM { blocking =>
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
    } yield new Service {
      override def getStrLn: ZIO[Any, IOException, String] =
        IO.effect {
          val r = reader.readLine()
          if (r == null) throw new EOFException("There is no more input left to read")
          else r
        }.refineToOrDie[IOException]
      override def putStrLn(msg: String): ZIO[Any, Nothing, Unit] =
        ZIO.effectTotal(System.out.println(msg))
    }
  }

  val brokenFast: ZLayer[Any, Nothing, NewConsole] = ZLayer.succeed {
    val reader = new BufferedReader(new InputStreamReader(System.in))
    val readLine: ZIO[Any, IOException, String] = IO.effect {
      val r = reader.readLine()
      if (r == null) throw new EOFException("eof")
      else r
    }.refineToOrDie[IOException]
    new Service {
      override def putStrLn(msg: String): ZIO[Any, Nothing, Unit] =
        ZIO.effectTotal(System.out.println(msg))

      override def getStrLn: ZIO[Any, IOException, String] = readLine
    }
  }

  val fast: ZLayer[Blocking, Nothing, NewConsole] = ZLayer.fromServiceM { blocking =>
    for {
      stdin   <- UIO(new FileInputStream(FileDescriptor.in))
      channel <- UIO(stdin.getChannel)

      interruptibleStdStream = new InputStream {
        override def read(): Int =
          sys.error("Unexpected operation performed by BufferedReader")
        override def available(): Int =
          stdin.available()

        override def read(b: Array[Byte], off: Int, len: Int): Int = {
          val data = ByteBuffer.allocate(len)
          val n = channel.read(data)
          if (n >= 0) {
            data.position(0)
            data.get(b, off, n)
            n
          } else n
        }
      }
      reader0 <- ZIO.effectTotal(new InputStreamReader(interruptibleStdStream))

      bufSize = 8 * 1024
      read = blocking.effectBlockingInterrupt {
        val data = new Array[Char](bufSize)
        val n = reader0.read(data, 0, data.length)
        if (n == -1) throw new EOFException("eof")
        Chunk.fromArray(data.take(n))
      }.refineToOrDie[IOException]

      reader <- ZBufferedReader.make[Any](read)
    } yield new Service {
      override def putStrLn(msg: String): ZIO[Any, Nothing, Unit] =
        ZIO.effectTotal(System.out.println(msg))
      override def getStrLn: ZIO[Any, IOException, String] =
        reader.readLine
    }
  }

  def putStrLn(line: => String): ZIO[NewConsole, Nothing, Unit] =
    ZIO.accessM(_.get putStrLn line)

  def getStrLn: ZIO[NewConsole, IOException, String] =
    ZIO.accessM(_.get getStrLn)
}

object Main extends zio.App {
  import zio.duration._
  import NewConsole._

  override def run(args: List[String]): ZIO[zio.ZEnv, Nothing, ExitCode] =
    for {
      t <- args match {
        case List(m, s) =>
          val layer = m match {
            case "old"            => UIO(NewConsole.old)
            case "jruby"          => UIO(NewConsole.jruby)
            case "fileDescriptor" => UIO(NewConsole.fileDescriptor)
            case "polling"        => UIO(NewConsole.polling)
            case "brokenFast"     => UIO(NewConsole.brokenFast)
            case "fast"           => UIO(NewConsole.fast)
            case _ => ZIO.die(new RuntimeException("Unknown layer."))
          }
          val program = s match {
            case "interrupt" =>
              val a = getStrLn.flatMap(putStrLn(_))
              val b = ZIO.sleep(10.second) *> putStrLn("No input")
              UIO((a race b).as(ExitCode.success))
            case "pipe" =>
              UIO(for {
                t0 <- clock.nanoTime
                r  <- getStrLn.flatMap(line => putStrLn(line)).forever.fold(_ => ExitCode.failure, _ => ExitCode.success)
                t1 <- clock.nanoTime
                _  <- UIO(System.err.println(((t1 - t0) / 1000000000.0).toString))
              } yield r)
            case _ => ZIO.die(new RuntimeException("Unknown scenario."))
          }
          layer <*> program
        case _ => ZIO.die(new RuntimeException("Bad args."))
      }
      (serviceLayer, program) = t

      result <- program.orDie.provideLayer(serviceLayer ++ Clock.any)
    } yield result
}
