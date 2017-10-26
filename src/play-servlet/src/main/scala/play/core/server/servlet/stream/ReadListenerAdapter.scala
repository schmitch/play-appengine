package play.core.server.servlet.stream

import java.io.IOException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.servlet.{ ReadListener, ServletInputStream }

import akka.util.ByteString
import play.core.server.servlet.stream.AkkaStreamReadListener._

private[servlet] final class ReadListenerAdapter(
    maxBufferSize: Int,
    sharedBuffer: BlockingQueue[AdapterToStageMessage],
    chunkSize: Int = 1024,
    inputStream: ServletInputStream,
    drain: AtomicBoolean
) extends ReadListener {

  private var buf: Option[ByteString] = None
  private var dataRead: Int           = 0

  override def onError(t: Throwable): Unit = {
    sharedBuffer.add(FailedRead(t))
  }

  override def onAllDataRead(): Unit = {
    buf match {
      case Some(buffer) =>
        sharedBuffer.add(Data(buffer))
        buf = None
        dataRead = 0
      case None =>
    }
    sharedBuffer.add(Finished)
  }

  private def readData(length: Int, b: Array[Byte]): Unit = {
    // read current data into a buffer
    // we actually assume that the buffer needs to be at least
    // the size of the data chunk
    val current = buf match {
      case Some(buffer) =>
        val in = buffer.concat(ByteString.fromArray(b, 0, length))
        buf = Some(in)
        in
      case None =>
        val in = ByteString.fromArray(b, 0, length)
        buf = Some(in)
        in
    }
    dataRead += length
    if (dataRead >= chunkSize && sharedBuffer.size() < (maxBufferSize -1)) {
      sharedBuffer.add(Data(current))
      buf = None
      dataRead = 0
    }
    if (sharedBuffer.size() >= (maxBufferSize -1)) {
      sharedBuffer.add(FailedRead(new IOException("buffer exceeded max size")))
    }
  }

  override def onDataAvailable(): Unit = {
    val b: Array[Byte] = new Array[Byte](chunkSize)

    var length = 0
    do {
      // read all available data
      length = inputStream.read(b)
      if (!drain.get()) {
        readData(length, b)
      }
    } while (inputStream.isReady && length != -1)

  }

}
