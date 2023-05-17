package plh40_iot.util

import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import akka.stream.scaladsl.RestartSource
import scala.concurrent.Promise
import akka.Done
import akka.stream.scaladsl.Source
import akka.stream.RestartSettings
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.RestartSink


object Utils {

    // Generic message type created for readabilty
    type ErrorMessage = String

    /** Tries to execute given code and returns either the defined value or an ErrorMessage. */
    def tryParse[C](code: => C): Either[ErrorMessage, C] = 
        try Right(code)
        catch {
            case (e: Exception) => Left("ERROR: " + e.toString())
        } 

    /** Tries to parse a message of generic type T using the provided function parse.*/
    def tryParse[T, Result](msg: T, parse: T => Result): Either[ErrorMessage, Result] = 
        tryParse(parse(msg))    

    /** Future value of trying to parse a string message using the provided function parse.*/
    def parseMessage[Result](msg: String, parse: String => Result)(implicit ec: ExecutionContext): Future[Either[ErrorMessage, Result]] = 
        Future { 
            tryParse(msg, parse)
        }

    /** Flow that diverts the result of a parsing function to different sink if an error occurs. 
     *  For now that sink defaults to printing the error message on stdout.
     */
    def errorHandleFlow[Result]() = 
        Flow[Either[ErrorMessage, Result]]
            .divertTo(Sink.foreach(println), _.isLeft)
            .collect { case Right(result) => result }


    /** Convenient method to get string represantation of current timestamp. */
    def currentTimestamp(): String =
        java.time.LocalDateTime.now().toString()

    /**
     * Wrap a source with restart logic and expose an equivalent materialized value.
     */
    def wrapWithAsRestartSource[M](restartSettings: RestartSettings, source: => Source[M, Future[Done]]): Source[M, Future[Done]] = {

        val fut = Promise[Done]()

        RestartSource
            .withBackoff(restartSettings) {
                () => source.mapMaterializedValue(mat => fut.completeWith(mat))
            }
            .mapMaterializedValue(_ => fut.future)
    }

    /**
     * Wrap a sink with restart logic and expose an equivalent materialized value.
     */
    def wrapWithAsRestartSink[M](restartSettings: RestartSettings, sink: => Sink[M, Future[Done]]): Sink[M, Future[Done]] = {

        val fut = Promise[Done]()

        RestartSink
            .withBackoff(restartSettings) {
                () => sink.mapMaterializedValue(mat => fut.completeWith(mat))
            }
            .mapMaterializedValue(_ => fut.future)
    }
}
