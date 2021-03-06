package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging

import scala.util.Try

trait Execution extends Runnable {
  def onShutdown(hint: String, f:()⇒Unit): Unit
  def complete(): Unit
  def future[T](value: T): FatalFuture[T]
}

trait FatalFuture[T] {
  def map(body: T ⇒ T): FatalFuture[T]
  def value: Option[Try[T]]
}

trait ExecutableApp {
  def execution: Runnable
}

trait Executable extends Runnable

trait Config {
  def get(key: String): String
}

////

object Trace extends LazyLogging { //m. b. to util
  def apply[T](f: =>T): T = try { f } catch {
    case e: Throwable =>
      logger.error("Trace",e)
      throw e
  }
}

object FinallyClose {
  def apply[A<:AutoCloseable,R](o: A)(f: A⇒R): R = try f(o) finally o.close()
  def apply[A,R](close: A⇒Unit)(o: A)(f: A⇒R): R = try f(o) finally close(o)
}
