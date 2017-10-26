package play.core.server.servlet.stream

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ BlockingQueue, LinkedBlockingDeque, TimeUnit }
import javax.servlet.ServletInputStream

import akka.stream._
import akka.stream.scaladsl.Source
import akka.stream.stage.{ GraphStageLogic, GraphStageWithMaterializedValue, OutHandler }
import akka.util.ByteString
import play.core.server.servlet.stream.AkkaStreamReadListener._

import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }

private[servlet] object AkkaStreamReadListener {

  sealed trait AdapterToStageMessage
  case object Finished extends AdapterToStageMessage
  case class Data(byte: ByteString) extends AdapterToStageMessage
  case class FailedRead(cause: Throwable) extends AdapterToStageMessage

  /**
   * FIXME: maybe this is not the sanest solution to convert the ServletInputStream to a AkkaStream, but it works!!!
   * bufferSize * chunkSize would be the total bytes stored per request at a maximum volume
   */
  def fromServletInputStream(
      createInputStream: () => ServletInputStream,
      maxBufferSize: Int = 1024, // FIXME: make me configurable
      chunkSize: Int = 1024,
      readTimeout: FiniteDuration = 5.seconds,
  ): Source[ByteString, Future[IOResult]] = {
    require(maxBufferSize > 1)
    val inputStream = createInputStream()
    val dataQueue = new LinkedBlockingDeque[AdapterToStageMessage](maxBufferSize)
    val drain = new AtomicBoolean(false) // FIXME: do we have any alternative here?
    inputStream.setReadListener(new ReadListenerAdapter(maxBufferSize, dataQueue, chunkSize, inputStream, drain))
    Source.fromGraph(new AkkaStreamReadListener(dataQueue, readTimeout, drain))
  }

}

private[servlet] final class AkkaStreamReadListener(
    sharedBuffer: BlockingQueue[AdapterToStageMessage],
    readTimeout: FiniteDuration,
    drain: AtomicBoolean
) extends GraphStageWithMaterializedValue[SourceShape[ByteString], Future[IOResult]] {
  private val out: Outlet[ByteString] = Outlet("AkkaServletInputStreamSource.out")
  override lazy val shape: SourceShape[ByteString] = SourceShape.of(out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[IOResult]) = {
    val promise: Promise[IOResult] = Promise()

    val logic = new GraphStageLogic(shape) {
      var servletFullyRead = false

      private def failAll(ex: Throwable): Unit = {
        servletFullyRead = true
        drain.set(true)
        sharedBuffer.clear() // dismiss all bytes that are still coming along
        promise.success(IOResult.createFailed(0, ex))
        failStage(ex)
      }

      val outHandler = new OutHandler {

        override def onDownstreamFinish(): Unit = {
          if (!promise.isCompleted) {
            if (!servletFullyRead) {
              failAll(new IOException("Servlet might not be read completly"))
            } else {
              promise.success(IOResult.createSuccessful(0))
            }
          }
        }

        override def onPull(): Unit = {
          try {
            sharedBuffer.poll(readTimeout.toMillis, TimeUnit.MILLISECONDS) match {
              case Data(data) =>
                push(out, data)
              case Finished =>
                servletFullyRead = true
                promise.success(IOResult.createSuccessful(0))
                completeStage()
              case FailedRead(ex) =>
                failAll(ex)
              case null ⇒
                failAll(new IOException("Timeout on waiting for new data"))
            }
          } catch {
            case ex: InterruptedException => failAll(new IOException(ex))
          }
        }

      }

      setHandler(out, outHandler)
    }

    (logic, promise.future)
  }

}
