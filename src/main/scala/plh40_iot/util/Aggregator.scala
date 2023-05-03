package plh40_iot.util

import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.pattern.StatusReply

// https://doc.akka.io/docs/akka/current/typed/interaction-patterns.html#general-purpose-response-aggregator
object Aggregator {

  sealed trait Command
  private case object ReceiveTimeout extends Command
  private case class WrappedReply[R](reply: R) extends Command

    def apply[Reply: ClassTag, Aggregate](
        sendRequests: ActorRef[Reply] => Unit,
        expectedReplies: Int,
        replyTo: ActorRef[Aggregate],
        aggregateReplies: immutable.IndexedSeq[Reply] => Aggregate,
        timeout: FiniteDuration
    ): Behavior[Command] = 
        Behaviors
            .setup { context =>
                context.setReceiveTimeout(timeout, ReceiveTimeout)
                val replyAdapter = context.messageAdapter[Reply](WrappedReply(_))
                sendRequests(replyAdapter)

                def collecting(replies: immutable.IndexedSeq[Reply]): Behavior[Command] = {
                    Behaviors.receiveMessage {
                    case WrappedReply(reply) =>
                        val newReplies = replies :+ reply.asInstanceOf[Reply]
                        if (newReplies.size == expectedReplies) {
                            val result = aggregateReplies(newReplies)
                            replyTo ! result
                            Behaviors.stopped
                        } else
                        collecting(newReplies)

                    case ReceiveTimeout =>
                        val aggregate = aggregateReplies(replies)
                        replyTo ! aggregate
                        Behaviors.stopped
                    }
                }

                collecting(Vector.empty)
            }

    def statusReplyCollector[T](
        sendRequests: ActorRef[StatusReply[T]] => Unit,
        expectedReplies: Int,
        replyTo: ActorRef[StatusReply[T]],
        aggregateReplies: immutable.IndexedSeq[StatusReply[T]] => StatusReply[T],
        timeout: FiniteDuration
    ): Behavior[Command] = 
        Aggregator(sendRequests, expectedReplies, replyTo, aggregateReplies, timeout)
}