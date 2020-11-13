package org.elasticmq.rest.sqs

import java.util.concurrent.Executors

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes.NotFound
import akka.http.scaladsl.server.Route
import org.elasticmq.QueueStatistics
import org.elasticmq.metrics.QueueMetricsOps
import org.elasticmq.rest.sqs.directives.ElasticMQDirectives
import org.elasticmq.util.NowProvider
import spray.json._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

final case class QueueResponse(
                                name: String,
                                statistics: Option[QueueStatisticsResponse]
                              )

final case class QueueStatisticsResponse(
                                          approximateNumberOfVisibleMessages: Long,
                                          approximateNumberOfInvisibleMessages: Long,
                                          approximateNumberOfMessagesDelayed: Long
                                        )


trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val queueStatisticsFormat = jsonFormat3(QueueStatisticsResponse)
  implicit val queueFormat = jsonFormat2(QueueResponse)
}

trait StatisticsDirectives extends JsonSupport {
  this: ElasticMQDirectives with QueueAttributesOps =>

  lazy val nowProvider = new NowProvider
  lazy val ec: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(2))
  implicit val duration = timeout.duration

  def statistics = {
    pathPrefix("statistics" / "queues") {
      concat(
        pathEndOrSingleSlash {
          complete {
            gatherAllQueuesWithStats
          }
        },
        path(Segment) { queueName =>
          gatherSpecificQueueWithStats(queueName)
        }
      )
    }
  }

  def gatherAllQueuesWithStats = {

    QueueMetricsOps.getQueuesStatistics(queueManagerActor, nowProvider)
      .map { x => x.map { case (name, stats) => mapToRest(name, Some(stats)) } }
  }

  def gatherSpecificQueueWithStats(queueName: String): Route = {
    val map = Map("QueueName" -> queueName)
    queueActorAndDataFromRequest(map) { (queueActor, queueData) =>
      onComplete(getAllQueueAttributes(map, queueActor, queueData)) {
        case Success(value) => complete(value)
        case Failure(ex) =>
          logger.error(s"Error while loading statistics for queue ${queueName}", ex)
          complete(NotFound, s"Can't load data for queue ${queueName}")
      }
    }
  }

  private def mapToRest(queueName: String, maybeStatistics: Option[QueueStatistics]) = {
    QueueResponse(queueName, maybeStatistics.map(stats => QueueStatisticsResponse(
      approximateNumberOfInvisibleMessages = stats.approximateNumberOfInvisibleMessages,
      approximateNumberOfMessagesDelayed = stats.approximateNumberOfMessagesDelayed,
      approximateNumberOfVisibleMessages = stats.approximateNumberOfVisibleMessages
    )))
  }
}
