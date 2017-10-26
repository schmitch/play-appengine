/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 * Copyright (C) 2017 envisia GmbH <http://www.envisia.de>
 *
 * [[akka.stream.impl.io.InputStreamSinkStage]]
 */
package play.core.server.servlet.stream

import java.util.concurrent.LinkedBlockingDeque
import javax.servlet.ServletOutputStream

import akka.stream.Attributes.InputBuffer
import akka.stream._
import akka.stream.scaladsl.{ Flow, Keep, Sink }
import akka.stream.stage._
import akka.util.ByteString
import play.core.server.servlet.stream.AkkaStreamWriteListener._

import scala.concurrent.duration._

private[servlet] object AkkaStreamWriteListener {

  sealed trait AdapterToStageMessage
  case class FailedWrite(cause: Throwable) extends AdapterToStageMessage

  sealed trait StreamToAdapterMessage
  case class Data(data: ByteString)   extends StreamToAdapterMessage
  case object Finished                extends StreamToAdapterMessage
  case object Initialized             extends StreamToAdapterMessage
  case class Failed(cause: Throwable) extends StreamToAdapterMessage

  sealed trait StageWithCallback {
    def wakeUp(msg: AdapterToStageMessage): Unit
  }

  def fromServletOutputStream(
      outputStream: ServletOutputStream,
      chunkSize: Int = 1024,
      writeTimeout: FiniteDuration = 30.seconds,
  ): Sink[ByteString, WriteListenerAdapter] = {
    Flow[ByteString]
            .via(new AkkaStreamChunker(chunkSize))
        .toMat(Sink.fromGraph(new AkkaStreamWriteListener(writeTimeout, outputStream)))(Keep.right)
  }

}

private[servlet] final class AkkaStreamWriteListener(writeTimeout: FiniteDuration, outputStream: ServletOutputStream)
    extends GraphStageWithMaterializedValue[SinkShape[ByteString], WriteListenerAdapter] {

  private val in: Inlet[ByteString]              = Inlet("AkkaStreamWriteListener.in")
  override lazy val shape: SinkShape[ByteString] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(
      inheritedAttributes: Attributes
  ): (GraphStageLogic, WriteListenerAdapter) = {
    val maxBuffer = inheritedAttributes.getAttribute(classOf[InputBuffer], InputBuffer(32, 32)).max
    val dataQueue = new LinkedBlockingDeque[StreamToAdapterMessage](maxBuffer + 2)

    val logic = new TimerGraphStageLogic(shape) with StageWithCallback with InHandler {
      var completionSignalled = false

      private val callback: AsyncCallback[AdapterToStageMessage] = {
        getAsyncCallback {
          case FailedWrite(t) ⇒ failStage(t)
        }
      }

      override def wakeUp(msg: AdapterToStageMessage): Unit = callback.invoke(msg)

      private def sendPullIfAllowed(): Unit = {
        if (dataQueue.remainingCapacity() > 1 && !hasBeenPulled(in)) {
          pull(in)
        } else {
          scheduleOnce(true, 100.millis)
        }
      }

      override def preStart(): Unit = {
        dataQueue.add(Initialized)
        pull(in)
      }

      def onPush(): Unit = {
        //1 is buffer for Finished or Failed callback
        require(dataQueue.remainingCapacity() > 1)
        dataQueue.add(Data(grab(in)))
        if (dataQueue.remainingCapacity() > 1) { sendPullIfAllowed() } else {
          println("buffer filled up")
          scheduleOnce(true, 100.millis)
        }
      }

      override def onUpstreamFinish(): Unit = {
        dataQueue.add(Finished)
        completionSignalled = true
        completeStage()
      }

      override def onUpstreamFailure(ex: Throwable): Unit = {
        dataQueue.add(Failed(ex))
        completionSignalled = true
        failStage(ex)
      }

      override def postStop(): Unit = {
        if (!completionSignalled) {
          dataQueue.add(Failed(new AbruptStageTerminationException(this)))
        }
      }

      override protected def onTimer(timerKey: Any): Unit = {
        sendPullIfAllowed()
      }

      setHandler(in, this)
    }

    (logic, new WriteListenerAdapter(dataQueue, logic.wakeUp, writeTimeout, outputStream))
  }

}
