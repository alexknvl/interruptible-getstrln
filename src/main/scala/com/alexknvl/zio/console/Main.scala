package com.alexknvl.zio.console

import java.io.{BufferedReader, FileDescriptor, FileInputStream, FilterInputStream, InputStream, InputStreamReader}
import java.nio.ByteBuffer

import zio.blocking.Blocking
import zio.clock.Clock
import zio.{Has, UIO, ZIO, ZLayer}

import scala.annotation.tailrec

object `package` {
  type NewConsole = Has[NewConsole.Service]
}

object NewConsole {
  trait Service {
    def putStrLn(msg: String): ZIO[Any, Nothing, Unit]
    def getStrLn: ZIO[Any, Nothing, String]
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
      override def getStrLn: ZIO[Any, Nothing, String] =
        blocking.effectBlockingInterrupt { reader.readLine() }.orDie

      override def putStrLn(msg: String): ZIO[Any, Nothing, Unit] =
        ZIO.effectTotal(System.out.println(msg))
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
      override def read(): Int =
        sys.error("Unexpected operation performed by BufferedReader")
      override def available(): Int =
        stdStream.available()

      override def read(b: Array[Byte], off: Int, len: Int): Int = {
        @tailrec def go(): Int =
          if (stdStream.available() > 0) {
            stdStream.read(b, off, len)
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
      override def getStrLn: ZIO[Any, Nothing, String] =
        blocking.effectBlockingInterrupt { reader.readLine() }.orDie
      override def putStrLn(msg: String): ZIO[Any, Nothing, Unit] =
        ZIO.effectTotal(System.out.println(msg))
    }
  }

  def putStrLn(line: => String): ZIO[NewConsole, Nothing, Unit] =
    ZIO.accessM(_.get putStrLn line)

  def getStrLn: ZIO[NewConsole, Nothing, String] =
    ZIO.accessM(_.get getStrLn)
}

object Main extends zio.App {
  import zio.duration._
  import NewConsole._

  override def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] =
    for {
      serviceLayer <- args match {
        case List("jruby")          => UIO(NewConsole.jruby)
        case List("fileDescriptor") => UIO(NewConsole.fileDescriptor)
        case List("polling")        => UIO(NewConsole.polling)
        case _ => ZIO.die(new RuntimeException("Unknown layer."))
      }

      program = {
        val a = getStrLn.flatMap(putStrLn(_))
        val b = ZIO.sleep(10.second) *> putStrLn("No input")
        (a race b).as(0)
      }

      result <- program.provideLayer(serviceLayer ++ Clock.any)
    } yield result
}
