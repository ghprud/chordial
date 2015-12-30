package com.tristanpenman.chordial.core

import akka.actor._
import akka.event.EventStream
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import com.tristanpenman.chordial.core.actors.CheckPredecessorAlgorithm._
import com.tristanpenman.chordial.core.actors.ClosestPrecedingFingerAlgorithm._
import com.tristanpenman.chordial.core.actors.FindPredecessorAlgorithm._
import com.tristanpenman.chordial.core.actors.FindSuccessorAlgorithm._
import com.tristanpenman.chordial.core.actors.NotifyAlgorithm._
import com.tristanpenman.chordial.core.actors.StabilisationAlgorithm._
import com.tristanpenman.chordial.core.actors._
import com.tristanpenman.chordial.core.shared.NodeInfo

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

class Coordinator(nodeId: Long, keyspaceBits: Int, requestTimeout: Timeout, livenessCheckDuration: Duration,
                  eventStream: EventStream)
  extends Actor with ActorLogging {

  import Coordinator._
  import Node._

  require(keyspaceBits > 0, "keyspaceBits must be a positive Int value")

  private val idModulus = 1 << keyspaceBits

  // Check that node ID is reasonable
  require(nodeId >= 0, "ownId must be a non-negative Long value")
  require(nodeId < idModulus, s"ownId must be less than $idModulus (2^$keyspaceBits})")

  private def newNode(nodeId: Long, seedId: Long, seedRef: ActorRef) =
    context.actorOf(Node.props(nodeId, keyspaceBits, NodeInfo(seedId, seedRef), eventStream))

  private def newCheckPredecessorAlgorithm() =
    context.actorOf(CheckPredecessorAlgorithm.props())

  private def newStabilisationAlgorithm() =
    context.actorOf(StabilisationAlgorithm.props())

  private def checkPredecessor(nodeRef: ActorRef, checkPredecessorAlgorithm: ActorRef, replyTo: ActorRef,
                               requestTimeout: Timeout) = {
    checkPredecessorAlgorithm.ask(CheckPredecessorAlgorithmStart(nodeRef, livenessCheckDuration))(requestTimeout)
      .mapTo[CheckPredecessorAlgorithmStartResponse]
      .map {
        case CheckPredecessorAlgorithmAlreadyRunning() =>
          CheckPredecessorInProgress()
        case CheckPredecessorAlgorithmOk() =>
          CheckPredecessorOk()
        case CheckPredecessorAlgorithmError(message) =>
          CheckPredecessorError(message)
      }
      .recover {
        case exception =>
          log.error(exception.getMessage)
          CheckPredecessorError("CheckPredecessor request failed due to internal error")
      }
      .pipeTo(replyTo)
  }

  private def closestPrecedingFinger(nodeRef: ActorRef, queryId: Long, replyTo: ActorRef, timeout: Timeout) = {
    val algorithm = context.actorOf(ClosestPrecedingFingerAlgorithm.props())
    algorithm.ask(ClosestPrecedingFingerAlgorithmStart(queryId, NodeInfo(nodeId, self), nodeRef))(timeout)
      .mapTo[ClosestPrecedingFingerAlgorithmStartResponse]
      .map {
        case ClosestPrecedingFingerAlgorithmOk(fingerId, fingerRef) =>
          ClosestPrecedingFingerOk(fingerId, fingerRef)
        case ClosestPrecedingFingerAlgorithmAlreadyRunning() =>
          throw new Exception("ClosestPrecedingFingerAlgorithm actor already running")
        case ClosestPrecedingFingerAlgorithmError(message) =>
          ClosestPrecedingFingerError(message)
      }
      .recover {
        case exception =>
          context.stop(algorithm)
          ClosestPrecedingFingerError(exception.getMessage)
      }
      .pipeTo(replyTo)
  }

  /**
   * Create an actor to execute the FindPredecessor algorithm and, when it finishes/fails, produce a response that
   * will be recognised by the original sender of the request
   *
   * This method passes in the ID and ActorRef of the current node as the initial candidate node, which means the
   * FindPredecessor algorithm will begin its search at the current node.
   */
  private def findPredecessor(queryId: Long, sender: ActorRef, algorithmTimeout: Timeout): Unit = {
    // The FindPredecessorAlgorithm actor will shutdown immediately after it sends a FindPredecessorAlgorithmOk or
    // FindPredecessorAlgorithmError message. However, if the future returned by the 'ask' request does not complete
    // within the timeout period, the actor must be shutdown manually to ensure that it does not run indefinitely.
    val findPredecessorAlgorithm = context.actorOf(FindPredecessorAlgorithm.props())
    findPredecessorAlgorithm.ask(FindPredecessorAlgorithmStart(queryId, nodeId, self))(algorithmTimeout)
      .mapTo[FindPredecessorAlgorithmStartResponse]
      .map {
        case FindPredecessorAlgorithmOk(predecessorId, predecessorRef) =>
          FindPredecessorOk(queryId, predecessorId, predecessorRef)
        case FindPredecessorAlgorithmAlreadyRunning() =>
          throw new Exception("FindPredecessorAlgorithm actor already running")
        case FindPredecessorAlgorithmError(message) =>
          FindPredecessorError(queryId, message)
      }
      .recover {
        case exception =>
          context.stop(findPredecessorAlgorithm)
          FindPredecessorError(queryId, exception.getMessage)
      }
      .pipeTo(sender)
  }

  /**
   * Create an actor to execute the FindSuccessor algorithm and, when it finishes/fails, produce a response that
   * will be recognised by the original sender of the request.
   *
   * This method passes in the ActorRef of the current node as the search node, which means the operation will be
   * performed in the context of the current node.
   */
  private def findSuccessor(queryId: Long, sender: ActorRef, algorithmTimeout: Timeout): Unit = {
    // The FindSuccessorAlgorithm actor will shutdown immediately after it sends a FindSuccessorAlgorithmOk or
    // FindSuccessorAlgorithmError message. However, if the future returned by the 'ask' request does not complete
    // within the timeout period, the actor must be shutdown manually to ensure that it does not run indefinitely.
    val findSuccessorAlgorithm = context.actorOf(FindSuccessorAlgorithm.props())
    findSuccessorAlgorithm.ask(FindSuccessorAlgorithmStart(queryId, self))(algorithmTimeout)
      .mapTo[FindSuccessorAlgorithmStartResponse]
      .map {
        case FindSuccessorAlgorithmOk(successorId, successorRef) =>
          FindSuccessorOk(queryId, successorId, successorRef)
        case FindSuccessorAlgorithmAlreadyRunning() =>
          throw new Exception("FindSuccessorAlgorithm actor already running")
        case FindSuccessorAlgorithmError(message) =>
          FindSuccessorError(queryId, message)
      }
      .recover {
        case exception =>
          context.stop(findSuccessorAlgorithm)
          FindSuccessorError(queryId, exception.getMessage)
      }
      .pipeTo(sender)
  }

  private def fixFingers(sender: ActorRef, algorithmTimeout: Timeout): Unit = {
    sender ! FixFingersError("FixFingers request handler not implemented")
  }

  private def notify(nodeRef: ActorRef, candidate: NodeInfo, replyTo: ActorRef, timeout: Timeout) = {
    val notifyAlgorithm = context.actorOf(NotifyAlgorithm.props())
    notifyAlgorithm.ask(NotifyAlgorithmStart(NodeInfo(nodeId, self), candidate, nodeRef))(timeout)
      .mapTo[NotifyAlgorithmStartResponse]
      .map {
        case NotifyAlgorithmOk(predecessorUpdated: Boolean) =>
          if (predecessorUpdated) {
            NotifyOk()
          } else {
            NotifyIgnored()
          }
        case NotifyAlgorithmAlreadyRunning() =>
          throw new Exception("NotifyAlgorithm actor already running")
        case NotifyAlgorithmError(message) =>
          NotifyError(message)
      }
      .recover {
        case exception =>
          context.stop(notifyAlgorithm)
          NotifyError(exception.getMessage)
      }
      .pipeTo(replyTo)
  }

  private def stabilise(nodeRef: ActorRef, stabilisationAlgorithm: ActorRef, replyTo: ActorRef,
                        requestTimeout: Timeout) = {
    stabilisationAlgorithm.ask(StabilisationAlgorithmStart(NodeInfo(nodeId, self), nodeRef))(requestTimeout)
      .mapTo[StabilisationAlgorithmStartResponse]
      .map {
        case StabilisationAlgorithmAlreadyRunning() =>
          StabiliseInProgress()
        case StabilisationAlgorithmOk() =>
          StabiliseOk()
        case StabilisationAlgorithmError(message) =>
          StabiliseError(message)
      }
      .recover {
        case exception =>
          log.error(exception.getMessage)
          StabiliseError("Stabilise request failed due to internal error")
      }
      .pipeTo(replyTo)
  }

  def receiveWhileReady(nodeRef: ActorRef,
                        checkPredecessorAlgorithm: ActorRef,
                        stabilisationAlgorithm: ActorRef): Receive = {
    case CheckPredecessor() =>
      checkPredecessor(nodeRef, checkPredecessorAlgorithm, sender(), requestTimeout)

    case m@ClosestPrecedingFinger(queryId: Long) =>
      closestPrecedingFinger(nodeRef, queryId, sender(), requestTimeout)

    case m@GetId() =>
      nodeRef.ask(m)(requestTimeout).pipeTo(sender())

    case m@GetPredecessor() =>
      nodeRef.ask(m)(requestTimeout).pipeTo(sender())

    case m@GetSuccessor() =>
      nodeRef.ask(m)(requestTimeout).pipeTo(sender())

    case FindPredecessor(queryId) =>
      findPredecessor(queryId, sender(), requestTimeout)

    case FindSuccessor(queryId) =>
      findSuccessor(queryId, sender(), requestTimeout)

    case FixFingers() =>
      fixFingers(sender(), requestTimeout)

    case Join(seedId, seedRef) =>
      context.stop(nodeRef)
      context.stop(checkPredecessorAlgorithm)
      context.stop(stabilisationAlgorithm)
      context.become(receiveWhileReady(newNode(nodeId, seedId, seedRef), newCheckPredecessorAlgorithm(),
        newStabilisationAlgorithm()))
      sender() ! JoinOk()

    case Notify(candidateId, candidateRef) =>
      notify(nodeRef, NodeInfo(candidateId, candidateRef), sender(), requestTimeout)

    case Stabilise() =>
      stabilise(nodeRef, stabilisationAlgorithm, sender(), requestTimeout)
  }

  override def receive: Receive = receiveWhileReady(newNode(nodeId, nodeId, self), newCheckPredecessorAlgorithm(),
    newStabilisationAlgorithm())
}

object Coordinator {

  sealed trait Request

  sealed trait Response

  case class CheckPredecessor() extends Request

  sealed trait CheckPredecessorResponse extends Response

  case class CheckPredecessorInProgress() extends CheckPredecessorResponse

  case class CheckPredecessorOk() extends CheckPredecessorResponse

  case class CheckPredecessorError(message: String) extends CheckPredecessorResponse

  case class ClosestPrecedingFinger(queryId: Long) extends Request

  sealed trait ClosestPrecedingFingerResponse extends Response

  case class ClosestPrecedingFingerOk(nodeId: Long, nodeRef: ActorRef) extends ClosestPrecedingFingerResponse

  case class ClosestPrecedingFingerError(message: String) extends ClosestPrecedingFingerResponse

  case class FindPredecessor(queryId: Long) extends Request

  sealed trait FindPredecessorResponse extends Response

  case class FindPredecessorOk(queryId: Long, predecessorId: Long, predecessorRef: ActorRef)
    extends FindPredecessorResponse

  case class FindPredecessorError(queryId: Long, message: String) extends FindPredecessorResponse

  case class FindSuccessor(queryId: Long) extends Request

  sealed trait FindSuccessorResponse extends Response

  case class FindSuccessorOk(queryId: Long, successorId: Long, successorRef: ActorRef)
    extends FindSuccessorResponse

  case class FindSuccessorError(queryId: Long, message: String) extends FindSuccessorResponse

  case class FixFingers() extends Request

  sealed trait FixFingersResponse extends Response

  case class FixFingersOk() extends FixFingersResponse

  case class FixFingersError(message: String) extends FixFingersResponse

  case class Join(seedId: Long, seedRef: ActorRef) extends Request

  sealed trait JoinResponse extends Response

  case class JoinOk() extends JoinResponse

  case class JoinError(message: String) extends JoinResponse

  case class Notify(nodeId: Long, nodeRef: ActorRef) extends Request

  sealed trait NotifyResponse extends Response

  case class NotifyOk() extends NotifyResponse

  case class NotifyIgnored() extends NotifyResponse

  case class NotifyError(message: String) extends NotifyResponse

  case class Stabilise() extends Request

  sealed trait StabiliseResponse extends Response

  case class StabiliseInProgress() extends StabiliseResponse

  case class StabiliseOk() extends StabiliseResponse

  case class StabiliseError(message: String) extends StabiliseResponse

  def props(nodeId: Long, keyspaceBits: Int, requestTimeout: Timeout, livenessCheckDuration: Duration,
            eventStream: EventStream): Props =
    Props(new Coordinator(nodeId, keyspaceBits, requestTimeout, livenessCheckDuration, eventStream))
}