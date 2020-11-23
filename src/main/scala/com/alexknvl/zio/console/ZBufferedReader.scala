package com.alexknvl.zio.console

import java.io.{EOFException, IOException}

import com.alexknvl.zio.console.ZBufferedReader.State
import zio.{Chunk, Ref, Semaphore, UIO, ZIO}

import scala.collection.mutable

final class ZBufferedReader[-R](pull: ZIO[R, IOException, Chunk[Char]], state: Ref[State], sema: Semaphore) {
  private[this] def split(buf: Chunk[Char]): (Chunk[Char], List[String]) = {
    def go(buf: Array[Char], start: Int, result: mutable.Builder[String, List[String]]): (Chunk[Char], List[String]) =
      buf.indexOf('\n', start) match {
        case -1 => (Chunk.fromArray(buf.slice(start, buf.length)), result.result())
        case i =>
          val line = new String(buf, start, i - start)
          go(buf, i + 1, result += line)
      }
    go(buf.toArray, 0, List.newBuilder)
  }

  def readLine: ZIO[R, IOException, String] = sema.withPermit {
    state.get.flatMap {
      case State(chars, h :: lines) =>
        state.set(State(chars, lines)).as(h)

      case State(chars, Nil) =>
        def go(chars: Chunk[Char]): ZIO[R, IOException, String] = pull.flatMap { c =>
          c.indexWhere(_ == '\n') match {
            case -1 => go(chars ++ c)
            case _ =>
              val (chars1, lines1) = split(chars ++ c)
              state.set(State(chars1, lines1.tail)).as(lines1.head)
          }
        }
        go(chars)
    }
  }
}
object ZBufferedReader {
  def make[R](read: ZIO[R, IOException, Chunk[Char]]): UIO[ZBufferedReader[R]] = for {
    ref <- Ref.make(State(Chunk.empty, Nil))
    sema <- Semaphore.make(1L)
  } yield new ZBufferedReader[R](read, ref, sema)

  private final case class State(leftover: Chunk[Char], lines: List[String])
}