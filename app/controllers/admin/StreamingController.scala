package controllers.admin

import java.io.{DataInputStream, File, FileInputStream}

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{FileIO, StreamConverters}
import javax.inject.{Inject, Singleton}
import play.api.http.HttpEntity
import play.api.libs.json.{Format, Json, OFormat}
import play.api.libs.streams.ActorFlow
import play.api.mvc.WebSocket.MessageFlowTransformer
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

@Singleton class StreamingController @Inject()(
  cc: ControllerComponents)(
  implicit ec: ExecutionContext,
  system: ActorSystem,
  mat: Materializer) extends AbstractController(cc) {

  def serveImage(pathname: String): Action[AnyContent] = Action { implicit request =>
    Ok.sendFile(
      content = new File(pathname),
      inline = false
    )
  }

  def serveVideo(pathname: String): Action[AnyContent] =
    serveFile(pathname, "audio/mpeg")

  def serveAudio(pathname: String): Action[AnyContent] =
    serveFile(pathname, "audio/mpeg")

  def serveFile(pathname: String, contentType: String): Action[AnyContent] = Action { implicit request =>
    val file = new File(pathname)
    val path = file.toPath
    val source = FileIO.fromPath(path)

    Result(
      header = ResponseHeader(200, Map.empty),
      body = HttpEntity.Streamed(
        source,
        Some(file.length), // Must specify content-length; Otherwise, Play will have to load the whole source into memory
        Some(contentType))
    )
  }

  def chunked(pathname: String, contentType: String): Action[AnyContent] = Action { implicit request =>
    val stream = new DataInputStream(new FileInputStream(pathname))
    val source = StreamConverters.fromInputStream(() => stream)
    Ok.chunked(source)
  }

  import InEvent._
  import OutEvent._

  implicit val messageFlowTransformer: MessageFlowTransformer[InEvent, OutEvent] =
    MessageFlowTransformer.jsonMessageFlowTransformer[InEvent, OutEvent]

  def socket: WebSocket = WebSocket.acceptOrResult[InEvent, OutEvent] { request =>
    Future.successful(request.session.get("username") match {
      case None => Left(Forbidden)
      case Some(_) =>
        Right(ActorFlow.actorRef { out =>
          MyWebSocketActor.props(out)
        })
    })
  }

}

case class InEvent(message: String)
object InEvent {
  implicit val InEventFormat: Format[InEvent] = Json.format[InEvent]
}

case class OutEvent(message: String)
object OutEvent {
  implicit val OutEventFormat: Format[OutEvent] = Json.format[OutEvent]
}


import akka.actor._

object MyWebSocketActor {
  def props(out: ActorRef) = Props(new MyWebSocketActor(out))
}

class MyWebSocketActor(out: ActorRef) extends Actor {
  def receive = {
    case msg: String =>
      out ! ("I received your message: " + msg)
  }
}