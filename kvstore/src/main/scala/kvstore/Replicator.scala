package kvstore

import akka.actor.{Actor, ActorRef, Cancellable, OneForOneStrategy, Props, ReceiveTimeout, SupervisorStrategy}
import kvstore.Persistence.{Persisted, PersistenceException}
import kvstore.Replica.OperationAck

import scala.concurrent.duration.*
import scala.language.postfixOps

object Replicator {
  case class Replicate(key: String, valueOption: Option[String], id: Long)
  case class Replicated(key: String, id: Long)

  case class Snapshot(key: String, valueOption: Option[String], seq: Long)
  case class SnapshotAck(key: String, seq: Long)

  def props(replica: ActorRef): Props = Props(new Replicator(replica))
}

class Replicator(val replica: ActorRef) extends Actor {
  import Replicator._
  import context.dispatcher

  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */

  // map from sequence number to pair of sender and request
  var acks = Map.empty[Long, (ActorRef, Replicate)]
  // a sequence of not-yet-sent snapshots (you can disregard this if not implementing batching)
  var pending = Vector.empty[Snapshot]
  var pendingSnaps = Map.empty[Long, Cancellable]

  var _seqCounter = 0L
  def nextSeq() = {
    val ret = _seqCounter
    _seqCounter += 1
    ret
  }

  private def getLasSeq() = _seqCounter - 1

  def receive: Receive = {
    case op: Replicate =>
      val seq = nextSeq()
      val tuple = (sender(), op)
      acks += (seq -> tuple)
      val cancellable = context.system.scheduler.schedule(0 seconds, 100 milliseconds)(replica ! Snapshot(op.key, op.valueOption, seq))
      pendingSnaps += (seq -> cancellable)
    case SnapshotAck(k, seq) =>
      val cancellable = pendingSnaps(seq)
      pendingSnaps -= seq
      cancellable.cancel()
      val (primary, op) = acks(seq)
      acks -= seq
      primary ! Replicated(k, op.id)
  }

}