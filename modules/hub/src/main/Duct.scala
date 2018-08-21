package lila.hub

import scala.concurrent.duration._
import scala.concurrent.stm._

import lila.base.LilaException

/*
 * Sequential like an actor, but for async functions,
 * and using an STM backend instead of akka actor.
 */
trait Duct {

  type ReceiveAsync = PartialFunction[Any, Fu[Any]]

  // implement async behaviour here
  protected val process: PartialFunction[Any, Fu[Any]]

  def !(msg: Any): Unit = atomic { implicit txn =>
    if (isBusy()) queue addLast msg
    else {
      isBusy() = true
      doProcess(msg)
    }
  }

  private[this] val isBusy = Ref(false)

  private[this] val queue = new java.util.ArrayDeque[Any]

  private[this] def doProcess(msg: Any): Fu[Any] =
    process.applyOrElse(msg, Duct.fallback) addEffectAnyway postProcess

  private[this] def postProcess = queue.poll match {
    case null => atomic { implicit txn =>
      isBusy() = false
    }
    case queuedMsg => doProcess(queuedMsg)
  }
}

object Duct {

  private val fallback = { msg: Any =>
    lila.log("Duct").warn(s"unhandled msg: $msg")
    funit
  }
}