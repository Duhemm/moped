package moped.internal.console

import java.util.concurrent.ExecutionException
import java.{util => ju}

/** Utility methods for trimming unrelated elements from stack traces */
class StackTraces
object StackTraces {
  @noinline
  def dropInside[T](t: => T): T = t
  @noinline
  def dropOutside[T](t: => T): T = t

  def trimStackTrace(ex: Throwable): Throwable = {
    val isVisited = ju
      .Collections
      .newSetFromMap(new ju.IdentityHashMap[Throwable, java.lang.Boolean]())
    def loop(e: Throwable): Unit = {
      if (e != null && isVisited.add(e)) {
        val stack = e.getStackTrace()
        if (stack != null) {
          e.setStackTrace(filterCallStack(stack))
        }
        loop(e.getCause())
      }
    }
    loop(ex)
    unboxException(ex)
  }

  private def unboxException(ex: Throwable): Throwable = {
    ex match {
      case e: ExecutionException if e.getCause != null && {
            e.getMessage match {
              case "Boxed Exception" | "Boxed Error" =>
                true
              case _ =>
                false
            }
          } =>
        e.getCause()
      case _ =>
        ex
    }
  }
  private val className = classOf[StackTraces].getCanonicalName() + "$"
  def filterCallStack(
      stack: Array[StackTraceElement]
  ): Array[StackTraceElement] = {
    val droppedInside = stack.lastIndexWhere(x =>
      x.getClassName == className && x.getMethodName == "dropInside"
    )

    val droppedOutside = stack.indexWhere(x =>
      x.getClassName == className && x.getMethodName == "dropOutside"
    )

    stack
      .view
      .slice(
        droppedInside match {
          case -1 =>
            0
          case n =>
            n + 3
        },
        droppedOutside match {
          case -1 =>
            stack.length
          case n =>
            n
        }
      )
      .filterNot(_.getClassName().startsWith("scala.runtime.java8.JFunction"))
      .toArray
  }
}
