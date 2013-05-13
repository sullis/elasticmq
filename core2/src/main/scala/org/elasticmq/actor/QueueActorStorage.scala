package org.elasticmq.actor

import scala.collection.mutable
import org.joda.time.DateTime
import org.elasticmq._
import org.elasticmq.data.{QueueData, NewMessageData, MessageData}
import org.elasticmq.MessageId
import org.elasticmq.MillisNextDelivery
import org.elasticmq.util.NowProvider

trait QueueActorStorage {
  def nowProvider: NowProvider
  def initialQueueData: QueueData

  var queueData = initialQueueData
  var messageQueue = mutable.PriorityQueue[InternalMessage]()
  val messagesById = mutable.HashMap[String, InternalMessage]()

  case class InternalMessage(id: String,
                             var deliveryReceipt: Option[String],
                             var nextDelivery: Long,
                             content: String,
                             created: DateTime,
                             var firstReceive: Received,
                             var receiveCount: Int)
    extends Comparable[InternalMessage] {

    // Priority queues have biggest elements first
    def compareTo(other: InternalMessage) = - nextDelivery.compareTo(other.nextDelivery)

    def toMessageData = MessageData(
      MessageId(id),
      deliveryReceipt.map(DeliveryReceipt(_)),
      content,
      MillisNextDelivery(nextDelivery),
      created,
      MessageStatistics(firstReceive, receiveCount))
  }

  object InternalMessage {
    def from(messageData: MessageData) = InternalMessage(
      messageData.id.id,
      messageData.deliveryReceipt.map(_.receipt),
      messageData.nextDelivery.millis,
      messageData.content,
      messageData.created,
      messageData.statistics.approximateFirstReceive,
      messageData.statistics.approximateReceiveCount)

    def from(newMessageData: NewMessageData) = InternalMessage(
      newMessageData.id.id,
      None,
      newMessageData.nextDelivery.toMillis(nowProvider.nowMillis).millis,
      newMessageData.content,
      nowProvider.now,
      NeverReceived,
      0)
  }
}