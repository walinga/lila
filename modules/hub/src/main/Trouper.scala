package lila.hub

import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.concurrent.stm._
import scala.concurrent.{ Future, Promise }

/*
 * Like an actor, but not an actor.
 * Uses an STM backend for sequentiality.
 * Has an unbounded (!) Queue of messages.
 * Like Duct, but for synchronous message processors.
 */
trait Trouper extends lila.common.Tellable {

  // implement async behaviour here
  protected val process: Trouper.Receive

  private[this] var alive = true

  def stop(): Unit = {
    alive = false
  }

  def !(msg: Any): Unit =
    if (alive && stateRef.single.getAndTransform { q =>
      Some(q.fold(Queue.empty[Any])(_ enqueue msg))
    }.isEmpty) run(msg)

  def ask[A](makeMsg: Promise[A] => Any): Fu[A] = {
    val promise = Promise[A]
    this ! makeMsg(promise)
    promise.future
  }

  // As fast as possible, at the expense of precision
  def estimateQueueSize = atomic { implicit txn =>
    stateRef.relaxedGet({ (_, _) => true }).??(_.size)
  }

  /*
   * Idle: None
   * Busy: Some(Queue.empty)
   * Busy with backlog: Some(Queue.nonEmpty)
   */
  private[this] val stateRef: Ref[Option[Queue[Any]]] = Ref(None)

  private[this] def run(msg: Any): Unit = Future {
    process.applyOrElse(msg, Trouper.fallback)
  } onComplete postRun

  private[this] val postRun = (_: Any) =>
    stateRef.single.getAndTransform {
      _ flatMap { q =>
        if (q.isEmpty) None else Some(q.tail)
      }
    } flatMap (_.headOption) foreach run

  lazy val uniqueId = Integer.toHexString(hashCode)
}

object Trouper {

  type Receive = PartialFunction[Any, Unit]

  private val fallback = { msg: Any =>
    lila.log("Trouper").warn(s"unhandled msg: $msg")
  }
}