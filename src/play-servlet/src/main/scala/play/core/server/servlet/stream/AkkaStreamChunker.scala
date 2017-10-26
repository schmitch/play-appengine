package play.core.server.servlet.stream

import akka.stream.stage._
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
import akka.util.ByteString

private[servlet] final class AkkaStreamChunker(val chunkSize: Int) extends GraphStage[FlowShape[ByteString, ByteString]] {
  private val in                                        = Inlet[ByteString]("Chunker.in")
  private val out                                       = Outlet[ByteString]("Chunker.out")
  override val shape: FlowShape[ByteString, ByteString] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private var buffer = ByteString.empty

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        if (isClosed(in)) emitChunk()
        else pull(in)
      }
    })
    setHandler(
      in,
      new InHandler {
        override def onPush(): Unit = {
          val elem = grab(in)
          buffer ++= elem
          emitChunk()
        }

        override def onUpstreamFinish(): Unit = {
          if (buffer.isEmpty) completeStage()
          else {
            // There are elements left in buffer, so
            // we keep accepting downstream pulls and push from buffer until emptied.
            //
            // It might be though, that the upstream finished while it was pulled, in which
            // case we will not get an onPull from the downstream, because we already had one.
            // In that case we need to emit from the buffer.
            if (isAvailable(out)) emitChunk()
          }
        }
      }
    )

    private def emitChunk(): Unit = {
      if (buffer.isEmpty) {
        if (isClosed(in)) completeStage()
        else pull(in)
      } else {
        val (chunk, nextBuffer) = buffer.splitAt(chunkSize)
        buffer = nextBuffer
        push(out, chunk)
      }
    }

  }
}
