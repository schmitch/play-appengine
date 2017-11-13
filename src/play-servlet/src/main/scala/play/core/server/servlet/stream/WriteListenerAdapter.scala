package play.core.server.servlet.stream

import java.io.IOException
import java.util.concurrent.{ BlockingQueue, TimeUnit }
import javax.servlet.{ ServletOutputStream, WriteListener }
import play.core.server.servlet.stream.AkkaStreamWriteListener._

import akka.stream.IOResult
import akka.util.ByteString

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ Future, Promise }

private[servlet] final class WriteListenerAdapter(
    sharedBuffer: BlockingQueue[StreamToAdapterMessage],
    sendToStage: (AdapterToStageMessage) ⇒ Unit,
    writeTimeout: FiniteDuration,
    outputStream: ServletOutputStream,
) extends WriteListener {
  private val finishedPromise: Promise[IOResult] = Promise()
  private[this] var isInitialized                = false
  private[this] var isStageAlive                 = true

  override def onError(t: Throwable): Unit = {
    if (isStageAlive) sendToStage(FailedWrite(t))
    finishedPromise.success(IOResult.createFailed(0, t))
  }

  @scala.throws(classOf[IOException])
  override def onWritePossible(): Unit = {
    waitIfNotInitialized()
    do {
      try {
          sharedBuffer.poll(writeTimeout.toMillis, TimeUnit.MILLISECONDS) match {
          case Data(data) =>
            writeBytes(data)
          case Finished =>
            isStageAlive = false
            finishedPromise.success(IOResult.createSuccessful(0))
          case Failed(ex) =>
            isStageAlive = false
            throw new IOException(ex)
          case null        ⇒ throw new IOException("Timeout on waiting for new data")
          case Initialized ⇒ throw new IllegalStateException("message 'Initialized' must come first")
        }
      } catch {
        case ex: InterruptedException => throw new IOException(ex)
      }
    } while (outputStream.isReady && isStageAlive)
  }

  private[this] def writeBytes(data: ByteString): Unit = {
    outputStream.write(data.toArray)
  }

  def waitUntilFinished: Future[IOResult] = finishedPromise.future

  private[this] def waitIfNotInitialized(): Unit = {
    if (!isInitialized) {
      sharedBuffer.poll(writeTimeout.toMillis, TimeUnit.MILLISECONDS) match {
        case Initialized ⇒ isInitialized = true
        case null        ⇒ throw new IOException(s"Timeout after $writeTimeout waiting for Initialized message from stage")
        case entry       ⇒ require(false, s"First message must be Initialized notification, got $entry")
      }
    }
  }

}
