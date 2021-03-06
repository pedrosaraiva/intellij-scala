package org.jetbrains.jps.incremental.scala
package remote

import java.io.{PrintWriter, PrintStream}

/**
 * @author Pavel Fatin
 */
class ClientEventProcessor(client: Client) {
  def process(event: Event) {
    event match {
      case MessageEvent(kind, text, source, line, column) =>
        client.message(kind, text, source, line, column)

      case ProgressEvent(text, done) =>
        client.progress(text, done)

      case GeneratedEvent(source, module, name) =>
        client.generated(source, module, name)

      case DeletedEvent(module) =>
        client.deleted(module)

      case TraceEvent(message, lines) =>
        client.trace(new ServerException(message, lines))
    }
  }
}

class ServerException(message: String, lines: Array[String]) extends Exception {
  override def getMessage = message

  override def printStackTrace(s: PrintWriter) {
    lines.foreach(s.println(_))
  }

  override def printStackTrace(s: PrintStream) {
    lines.foreach(s.println(_))
  }
}
