package zio

import java.io.{EOFException, FileDescriptor, FileInputStream, FileOutputStream, IOException, InputStream, InputStreamReader, OutputStream, OutputStreamWriter, PrintStream}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

import com.github.ghik.silencer.silent
import zio.blocking.Blocking

package object console2 {
  type Console2 = Has[Console2.Service]

  object Console2 {
    private[zio] def unsafeInterruptibleStream(in: FileInputStream): InputStream =
      new InputStream {
        val channel: FileChannel = in.getChannel

        override def read(): Int =
          throw new UnsupportedOperationException()
        override def available(): Int =
          in.available()

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

    trait Service extends Serializable {
      def putStr(line: String): UIO[Unit]

      def putStrLn(msg: String): UIO[Unit]

      def putStrErr(line: String): UIO[Unit]

      def putStrLnErr(line: String): UIO[Unit]

      def getStrLn: IO[IOException, String]
    }

    val any: ZLayer[Console2, Nothing, Console2] =
      ZLayer.requires[Console2]

    val live: ZLayer[Blocking, Nothing, Console2] = ZLayer.fromServiceM { blocking =>
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
  }

  /**
   * Prints text to the console.
   */
  def putStr(line: => String): URIO[Console2, Unit] =
    ZIO.accessM(_.get putStr line)

  /**
   * Prints text to the standard error console.
   */
  def putStrErr(line: => String): URIO[Console2, Unit] =
    ZIO.accessM(_.get putStrErr line)

  /**
   * Prints a line of text to the console, including a newline character.
   */
  def putStrLn(line: => String): URIO[Console2, Unit] =
    ZIO.accessM(_.get putStrLn line)

  /**
   * Prints a line of text to the standard error console, including a newline character.
   */
  def putStrLnErr(line: => String): URIO[Console2, Unit] =
    ZIO.accessM(_.get putStrLnErr line)

  /**
   * Retrieves a line of input from the console.
   */
  val getStrLn: ZIO[Console2, IOException, String] =
    ZIO.accessM(_.get.getStrLn)
}