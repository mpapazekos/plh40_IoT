package plh40_iot.util

import akka.kafka.ConsumerMessage.CommittableOffset
import akka.kafka.ConsumerSettings
import akka.kafka.ProducerSettings
import akka.kafka.Subscription
import akka.kafka.scaladsl.Consumer
import akka.kafka.scaladsl.Producer
import akka.stream.RestartSettings
import akka.stream.scaladsl.RestartSink
import akka.stream.scaladsl.SourceWithContext
import com.typesafe.config.ConfigFactory
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import akka.stream.scaladsl.Sink
import org.apache.kafka.clients.producer.ProducerRecord
import akka.NotUsed

/**
  * Uses alpakka kafka connector 
  * to generalize methods used 
  * in order to connect to a kafka broker 
  */
object KafkaConnector {
 
  private val defaultRestartSettings = 
    RestartSettings(1.second, 10.seconds, randomFactor = 0.2d).withMaxRestarts(20, 5.minutes)
  /**
    * Provides a commitableSource with the kafka offset as context
    * which subcribes to given topics and parses string messages 
    * with given function (parseMessage). The parsing result is 
    * passed through the default error handle flow
    * which diverts error results to another sink (Sink.println). 
    */
  def committableSourceWithOffsetContext[Result](
    consumerSettings: ConsumerSettings[String, String], 
    topics: Subscription, 
    parseMessage: String => Result
  )(implicit ec: ExecutionContext): SourceWithContext[Result, CommittableOffset, Consumer.Control] = {
    Consumer
          .committableSource(consumerSettings, topics)
          .mapAsync(4)(msg => parseWithOffset(msg.record.value(), msg.committableOffset, parseMessage))
          .via(Utils.errorHandleFlow())
          .asSourceWithContext(resOffest => resOffest._2)
          .map(resOffest => resOffest._1)
  }

  /**
    * Creates kafka producer settings for given client id 
    * based on application configuration. 
    */
  def producerSettings(clientId: String): ProducerSettings[String, String] = { 
      val config = ConfigFactory.load("kafka").getConfig("akka.kafka.producer")
        
      ProducerSettings(config, new StringSerializer, new StringSerializer)
          .withClientId(clientId)       
  }

   /**
    * Creates kafka consumer settings for given client id 
    * based on application configuration. 
    */
  def consumerSettings(clientId: String, groupId: String): ConsumerSettings[String, String] = { 
      val config = ConfigFactory.load("kafka").getConfig("akka.kafka.consumer")
        
      ConsumerSettings(config, new StringDeserializer, new StringDeserializer)
          .withClientId(clientId)
          .withGroupId(groupId)
  }

  /**
    * Provides an kafka plain Sink with given producer and restart settings.
    */
  def plainRestartProducer(
    producerSettings: ProducerSettings[String, String]
  )(implicit restartSettings: RestartSettings = defaultRestartSettings): Sink[ProducerRecord[String,String], NotUsed] = 
    RestartSink
      .withBackoff(restartSettings)(() => Producer.plainSink(producerSettings))
  
  /**
    * Helper method for parsing a string message asynchronously 
    * with error handling reporting using Either.
    * @param msg The string message to parse
    * @param offset Offset to keep if parsed successfully
    * @param parse Function used for parsing 
    * @param ec Execution context for asynchronous functionality
    * @return Future with an Either parse result.
    */
  private def parseWithOffset[Result](
    msg: String, offset: CommittableOffset, parse: String => Result
  )(implicit ec: ExecutionContext): Future[Either[String, (Result, CommittableOffset)]] = 
    Utils.parseMessage(msg, msg => (parse(msg), offset))
    
}
