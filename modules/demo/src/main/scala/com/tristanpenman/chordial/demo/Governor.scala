package com.tristanpenman.chordial.demo

import akka.actor._
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import com.tristanpenman.chordial.core.Node
import com.tristanpenman.chordial.core.Node._
import com.tristanpenman.chordial.core.Event.NodeShuttingDown
import com.tristanpenman.chordial.core.Pointers.{GetSuccessorList, GetSuccessorListOk, GetSuccessorListResponse}

import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Random, Success}

class Governor(val keyspaceBits: Int) extends Actor with ActorLogging {

  import Governor._

  require(keyspaceBits > 0, "keyspaceBits must be a positive Int value")

  private val idModulus = 1 << keyspaceBits

  // How long to wait when making requests that may be routed to other nodes
  private val externalRequestTimeout = Timeout(500.milliseconds)

  // How long Node should wait until an algorithm is considered to have timed out. This should be significantly
  // longer than the external request timeout, as some algorithms will make multiple external requests before
  // running to completion
  private val algorithmTimeout = Timeout(5000.milliseconds)

  private val checkPredecessorTimeout = Timeout(2500.milliseconds)
  private val fixFingersTimeout = Timeout(3000.milliseconds)
  private val livenessCheckDuration = 2000.milliseconds
  private val joinRequestTimeout = Timeout(2000.milliseconds)
  private val getSuccessorRequestTimeout = Timeout(2000.milliseconds)
  private val stabiliseTimeout = Timeout(1500.milliseconds)

  private def scheduleCheckPredecessor(nodeRef: ActorRef) =
    context.system.scheduler.schedule(300.milliseconds, 300.milliseconds) {
      nodeRef.ask(CheckPredecessor())(checkPredecessorTimeout)
        .mapTo[CheckPredecessorResponse]
        .onComplete {
          case util.Success(result) => result match {
            case CheckPredecessorOk() =>
              log.debug("CheckPredecessor (requested for {}) finished successfully", nodeRef.path)
            case CheckPredecessorInProgress() =>
              log.warning("CheckPredecessor (requested for {}) already in progress", nodeRef.path)
            case CheckPredecessorError(message) =>
              log.error("CheckPredecessor (requested for {}) finished with error: {}", nodeRef.path, message)
          }
          case util.Failure(exception) =>
            log.error("CheckPredecessor (requested for {}) failed with an exception: {}", nodeRef.path, exception)
        }
    }

  private def scheduleFixFingers(nodeRef: ActorRef) =
    context.system.scheduler.schedule(500.milliseconds, 500.milliseconds) {
      nodeRef.ask(FixFingers())(fixFingersTimeout)
        .mapTo[FixFingersResponse]
        .onComplete {
          case util.Success(result) => result match {
            case FixFingersOk() =>
              log.debug("FixFingers (requested for {}) finished successfully", nodeRef.path)
            case FixFingersInProgress() =>
              log.debug("FixFingers (requested for {}) already in progress", nodeRef.path)
            case FixFingersError(message) =>
              log.debug("FixFingers (requested for {}) finished with error: {}", nodeRef.path, message)
          }
          case util.Failure(exception) =>
            log.error("FixFingers (requested for {}) failed with an exception: {}", nodeRef.path, exception)
        }

    }

  private def scheduleStabilisation(nodeRef: ActorRef) =
    context.system.scheduler.schedule(200.milliseconds, 200.milliseconds) {
      nodeRef.ask(Stabilise())(stabiliseTimeout)
        .mapTo[StabiliseResponse]
        .onComplete {
          case util.Success(result) => result match {
            case StabiliseOk() =>
              log.debug("Stabilisation (requested for {}) finished successfully", nodeRef.path)
            case StabiliseInProgress() =>
              log.warning("Stabilisation (requested for {}) already in progress", nodeRef.path)
            case StabiliseError(message) =>
              log.error("Stabilisation (requested for {}) finished with error: {}", nodeRef.path, message)
          }
          case util.Failure(exception) =>
            log.error("Stabilisation (requested for {}) failed with an exception: {}", nodeRef.path, exception)
        }
    }

  private def createNode(nodeId: Long): ActorRef = {
    context.system.actorOf(Node.props(nodeId, keyspaceBits, algorithmTimeout, externalRequestTimeout,
      livenessCheckDuration, context.system.eventStream))
  }

  @tailrec
  private def generateUniqueId(nodeIds: Set[Long]): Long = {
    val id = Random.nextInt(idModulus)
    if (!nodeIds.contains(id)) {
      id
    } else {
      generateUniqueId(nodeIds)
    }
  }

  private def receiveWithNodes(nodes: Map[Long, ActorRef],
                               terminatedNodes: Set[Long],
                               stabilisationCancellables: Map[Long, Cancellable],
                               checkPredecessorCancellables: Map[Long, Cancellable],
                               fixFingersCancellables: Map[Long, Cancellable]): Receive = {
    case CreateNode() =>
      if (nodes.size < idModulus) {
        val nodeId = generateUniqueId(nodes.keySet ++ terminatedNodes)
        val nodeRef = createNode(nodeId)
        val stabilisationCancellable = scheduleStabilisation(nodeRef)
        val checkPredecessorCancellable = scheduleCheckPredecessor(nodeRef)
        val fixFingersCancellable = scheduleFixFingers(nodeRef)
        context.become(receiveWithNodes(nodes + (nodeId -> nodeRef), terminatedNodes,
          stabilisationCancellables + (nodeId -> stabilisationCancellable),
          checkPredecessorCancellables + (nodeId -> checkPredecessorCancellable),
          fixFingersCancellables + (nodeId -> fixFingersCancellable)))
        sender() ! CreateNodeOk(nodeId, nodeRef)
      } else {
        sender() ! CreateNodeInvalidRequest(s"Maximum of $idModulus Chord nodes already created")
      }

    case CreateNodeWithSeed(seedId) =>
      nodes.get(seedId) match {
        case Some(seedRef) =>
          if (nodes.size < idModulus) {
            val nodeId = generateUniqueId(nodes.keySet ++ terminatedNodes)
            val nodeRef = createNode(nodeId)
            val joinRequest = nodeRef.ask(Join(seedId, seedRef))(joinRequestTimeout)
              .mapTo[JoinResponse]
              .map {
                case JoinOk() => Success(())
                case JoinError(message) => throw new Exception(message)
              }
              .recover {
                case ex => Failure(ex)
              }

            Await.result(joinRequest, Duration.Inf) match {
              case Success(()) =>
                val stabilisationCancellable = scheduleStabilisation(nodeRef)
                val checkPredecessorCancellable = scheduleCheckPredecessor(nodeRef)
                val fixFingersCancellable = scheduleFixFingers(nodeRef)
                context.become(receiveWithNodes(nodes + (nodeId -> nodeRef), terminatedNodes,
                  stabilisationCancellables + (nodeId -> stabilisationCancellable),
                  checkPredecessorCancellables + (nodeId -> checkPredecessorCancellable),
                  fixFingersCancellables + (nodeId -> fixFingersCancellable)))
                sender() ! CreateNodeWithSeedOk(nodeId, nodeRef)
              case Failure(ex) =>
                context.stop(nodeRef)
                sender() ! CreateNodeWithSeedInternalError(ex.getMessage)
            }
          } else {
            sender() ! CreateNodeWithSeedInvalidRequest(s"Maximum of $idModulus Chord nodes already created")
          }

        case None =>
          sender() ! CreateNodeWithSeedInvalidRequest(s"Node with ID $seedId does not exist")
      }

    case GetNodeIdSet() =>
      sender() ! GetNodeIdSetOk(nodes.keySet ++ terminatedNodes)

    case GetNodeState(nodeId: Long) =>
      if (nodes.contains(nodeId)) {
        sender() ! GetNodeStateOk(true)
      } else if (terminatedNodes.contains(nodeId)) {
        sender() ! GetNodeStateOk(false)
      } else {
        sender() ! GetNodeStateError(s"Node with ID $nodeId does not exist")
      }

    case GetNodeSuccessorId(nodeId: Long) =>
      nodes.get(nodeId) match {
        case Some(nodeRef) =>
          val getSuccessorRequest = nodeRef.ask(GetSuccessorList())(getSuccessorRequestTimeout)
            .mapTo[GetSuccessorListResponse]
            .map {
              case GetSuccessorListOk(primarySuccessor, _) => GetNodeSuccessorIdOk(primarySuccessor.id)
            }
            .recover {
              case ex => GetNodeSuccessorIdError(ex.getMessage)
            }
            .pipeTo(sender())

        case None =>
          if (terminatedNodes.contains(nodeId)) {
            sender() ! GetNodeSuccessorIdInvalidRequest(s"Node with ID $nodeId is no longer active")
          } else {
            sender() ! GetNodeSuccessorIdInvalidRequest(s"Node with ID $nodeId does not exist")
          }
      }

    case TerminateNode(nodeId: Long) =>
      nodes.get(nodeId) match {
        case Some(nodeRef) =>
          checkPredecessorCancellables.get(nodeId).foreach(_.cancel())
          stabilisationCancellables.get(nodeId).foreach(_.cancel())
          fixFingersCancellables.get(nodeId).foreach(_.cancel())
          context.stop(nodeRef)
          context.become(receiveWithNodes(nodes - nodeId, terminatedNodes + nodeId, stabilisationCancellables - nodeId,
            checkPredecessorCancellables - nodeId, fixFingersCancellables - nodeId))
          sender() ! TerminateNodeResponseOk()
          context.system.eventStream.publish(NodeShuttingDown(nodeId))

        case None =>
          sender() ! TerminateNodeResponseError(s"Node with ID $nodeId does not exist")
      }
  }

  override def receive: Receive = receiveWithNodes(Map.empty, Set.empty, Map.empty, Map.empty, Map.empty)
}

object Governor {

  sealed trait Request

  sealed trait Response

  case class CreateNode() extends Request

  sealed trait CreateNodeResponse extends Response

  case class CreateNodeOk(nodeId: Long, nodeRef: ActorRef) extends CreateNodeResponse

  case class CreateNodeInternalError(message: String) extends CreateNodeResponse

  case class CreateNodeInvalidRequest(message: String) extends CreateNodeResponse

  case class CreateNodeWithSeed(seedId: Long) extends Request

  sealed trait CreateNodeWithSeedResponse extends Response

  case class CreateNodeWithSeedOk(nodeId: Long, nodeRef: ActorRef) extends CreateNodeWithSeedResponse

  case class CreateNodeWithSeedInternalError(message: String) extends CreateNodeWithSeedResponse

  case class CreateNodeWithSeedInvalidRequest(message: String) extends CreateNodeWithSeedResponse

  case class GetNodeIdSet() extends Request

  sealed trait GetNodeIdSetResponse extends Response

  case class GetNodeIdSetOk(nodeIds: Set[Long]) extends GetNodeIdSetResponse

  case class GetNodeState(nodeId: Long) extends Request

  sealed trait GetNodeStateResponse extends Response

  case class GetNodeStateOk(active: Boolean) extends GetNodeStateResponse

  case class GetNodeStateError(message: String) extends GetNodeStateResponse

  case class GetNodeStateInvalidRequest(message: String) extends GetNodeStateResponse

  case class GetNodeSuccessorId(nodeId: Long) extends Request

  sealed trait GetNodeSuccessorIdResponse extends Response

  case class GetNodeSuccessorIdOk(successorId: Long) extends GetNodeSuccessorIdResponse

  case class GetNodeSuccessorIdError(message: String) extends GetNodeSuccessorIdResponse

  case class GetNodeSuccessorIdInvalidRequest(message: String) extends GetNodeSuccessorIdResponse

  case class TerminateNode(nodeId: Long) extends Request

  sealed trait TerminateNodeResponse extends Response

  case class TerminateNodeResponseOk() extends TerminateNodeResponse

  case class TerminateNodeResponseError(message: String) extends TerminateNodeResponse

  def props(keyspaceBits: Int): Props = Props(new Governor(keyspaceBits))

}
