package com.iheart.workpipeline.akka.patterns.queue

import akka.actor._
import com.iheart.workpipeline.akka.helpers.MessageScheduler
import com.iheart.workpipeline.akka.patterns
import patterns.CommonProtocol.QueryStatus
import CommonProtocol.{ WorkTimedOut, WorkFailed }
import com.iheart.workpipeline.akka.patterns.queue.QueueProcessor.{ WorkCompleted, MissionAccomplished }
import Queue.{ Unregistered, Unregister, NoWorkLeft, RequestWork }
import Worker._

import scala.concurrent.duration._

trait Worker extends Actor with ActorLogging with MessageScheduler {

  protected def delegateeProps: Props //actor who really does the work
  protected val queue: ActorRef
  protected def monitor: ActorRef = context.parent

  def receive = idle()

  context watch queue

  override def preStart(): Unit = {
    askMoreWork(None)
  }

  lazy val delegatee = {
    val ref = context.actorOf(delegateeProps, "delegatee")
    context watch ref
    ref
  }

  def idle(delayBeforeNextWork: Option[FiniteDuration] = None): Receive = {

    case Hold(period) ⇒ context become idle(Some(period))

    case work: Work => sendWorkToDelegatee(work, 0, delayBeforeNextWork)

    case NoWorkLeft =>
      monitor ! MissionAccomplished(self) //todo: maybe a simple stop is good enough?
      finish()

    case Worker.Retire =>
      queue ! Unregister(self)
      context become retiring(None)

    case qs: QueryStatus => qs reply Idle

    case Terminated(`queue`) => finish()
  }

  def finish(): Unit = context stop self

  def working(outstanding: Outstanding, delayBeforeNextWork: Option[FiniteDuration] = None): Receive = ({
    case Hold(period) ⇒ context become working(outstanding, Some(period))

    case Terminated(`queue`) => context become retiring(Some(outstanding))

    case qs: QueryStatus => qs reply Working

    case Worker.Retire => context become retiring(Some(outstanding))

  }: Receive).orElse(

    waitingResult(outstanding, false, delayBeforeNextWork)
  )

    .orElse {
      case msg => log.error(s"unrecognized interrupting msg during working $msg")
    }

  def retiring(outstanding: Option[Outstanding]): Receive = ({
    case Terminated(`queue`) => //ignore when retiring
    case qs: QueryStatus => qs reply Retiring
    case Unregistered => finish()
    case Retire => //already retiring
  }: Receive) orElse (
    if (outstanding.isDefined)
      waitingResult(outstanding.get, true, None)
    else {
      case w: Work =>
        sender ! Rejected(w, "Retiring")
        finish()
    }
  )

  def waitingResult(
    outstanding: Outstanding,
    isRetiring: Boolean,
    delayBeforeNextWork: Option[FiniteDuration]
  ): Receive = ({

    case DelegateeTimeout =>
      log.error(s"${delegatee.path} timed out after ${outstanding.work.settings.timeout} work ${outstanding.work.messageToDelegatee} abandoned")
      outstanding.timeout()

      if (isRetiring) finish() else {
        askMoreWork(delayBeforeNextWork)
      }
    case w: Work => sender ! Rejected(w, "busy") //just in case

  }: Receive) orElse resultChecker.andThen[Unit] {
    case Right(result) =>
      outstanding.success(result)
      if (isRetiring) finish() else {
        askMoreWork(delayBeforeNextWork)
      }
    case Left(e) =>
      log.error(s"error $e returned by delegatee in regards to running work $outstanding")
      retryOrAbandon(outstanding, isRetiring, e, delayBeforeNextWork)

  }

  private def retryOrAbandon(
    outstanding: Outstanding,
    isRetiring: Boolean,
    error: Any,
    delayBeforeNextWork: Option[FiniteDuration]
  ): Unit = {
    outstanding.cancel()
    if (outstanding.retried < outstanding.work.settings.retry && delayBeforeNextWork.isEmpty) {
      log.info(s"Retry work $outstanding")
      sendWorkToDelegatee(outstanding.work, outstanding.retried + 1, None)
    } else {
      val message = s"Work failed after ${outstanding.retried} try(s)"
      log.error(s"$message, work $outstanding abandoned")
      outstanding.fail(WorkFailed(message + s" due to $error"))
      if (isRetiring) finish()
      else
        askMoreWork(delayBeforeNextWork)
    }
  }

  private def sendWorkToDelegatee(work: Work, retried: Int, delay: Option[FiniteDuration]): Unit = {
    val timeoutHandle: Cancellable = delayedMsg(delay.fold(work.settings.timeout)(_ + work.settings.timeout), DelegateeTimeout)
    maybeDelayedMsg(delay, work.messageToDelegatee, delegatee)
    context become working(Outstanding(work, timeoutHandle, retried))
  }

  private def askMoreWork(delay: Option[FiniteDuration]): Unit = {
    maybeDelayedMsg(delay, RequestWork(self), queue)
    context become idle()
  }

  protected def resultChecker: ResultChecker

  protected case class Outstanding(work: Work, timeoutHandle: Cancellable, retried: Int = 0) {
    def success(result: Any): Unit = {
      monitor ! WorkCompleted(self)
      done(result)
    }

    def fail(result: Any): Unit = {
      monitor ! WorkFailed(result.toString)
      done(result)
    }

    def timeout(): Unit = {
      monitor ! WorkTimedOut("unknown")
      done(WorkTimedOut(s"Delegatee didn't respond within ${work.settings.timeout}"))
    }

    protected def done(result: Any): Unit = {
      cancel()
      reportResult(result)
    }

    def cancel(): Unit = if (!timeoutHandle.isCancelled) timeoutHandle.cancel()

    override def toString = work.messageToDelegatee.getClass.toString

    def reportResult(result: Any): Unit = work.settings.sendResultTo.foreach(_ ! result)

  }
}

object Worker {

  private case object DelegateeTimeout
  case object Retire

  sealed trait WorkerStatus
  case object Retiring extends WorkerStatus
  case object Idle extends WorkerStatus
  case object Working extends WorkerStatus

  case class Hold(period: FiniteDuration)

  class DefaultWorker(
    protected val queue: QueueRef,
      protected val delegateeProps: Props,
      protected val resultChecker: ResultChecker
  ) extends Worker {

    val resultHistoryLength = 0

  }

  def default(
    queue: QueueRef,
    delegateeProps: Props
  )(resultChecker: ResultChecker): Props = {
    Props(new DefaultWorker(queue, delegateeProps, resultChecker))
  }

}

