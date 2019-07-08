package controllers.admin

import java.io.File

import akka.stream.Materializer
import akka.util.ByteString
import javax.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.libs.json.JsValue
import play.api.libs.streams.Accumulator
import play.api.libs.ws.{WSClient, WSRequest, WSResponse}
import play.api.mvc._

import scala.concurrent.ExecutionContext

@Singleton class BodyParsers @Inject()(
  wsClient: WSClient,
  configuration: Configuration,
  cc: ControllerComponents)(implicit ec: ExecutionContext, mat: Materializer) extends AbstractController(cc) {

  private val uploadsRoot = configuration.get[String]("admin.config.body-parsers.uploads.root")

  private val uploadsPath = configuration.get[String]("admin.config.body-parsers.uploads.path")

  private val forwardStreamUrl = configuration.get[String]("admin.config.body-parsers.streaming.url")

  /*
  curl \
    --header "Content-type: application/json" \
    --request POST \
    --data '{"symbol":"GOOG", "price":900.00}' \
    http://localhost:9000/parser/json
   */
  def parseJson(maxLength: Int): Action[JsValue] =
    Action(parse.json(maxLength)) { implicit request: Request[JsValue] =>
      Ok(request.body)
    }

  /*
  curl \
    --request POST \
    --data '{"symbol":"GOOG", "price":900.00}' \
    http://localhost:9000/parser/json/tolerant
  */
  def parseJsonIgnoreContentType(maxLength: Int): Action[JsValue] =
    Action(parse.tolerantJson(maxLength)) { implicit request: Request[JsValue] =>
      Ok(request.body)
    }

  private def file(pathname: String): File = {
    val file = new File(pathname)
    file.getParentFile.mkdirs()
    file
  }

  private def saveInFile(path: String): BodyParser[File] = parse.using { request =>
    request
      .session
      .get("username")
      .map(username => parse.file(file(s"/$uploadsRoot/$username/$path")))
      .getOrElse(parse.file(file(s"/$uploadsRoot/$uploadsPath/$path")))
  }

  /*
  curl \
    --header "Content-type: application/json" \
    --request POST \
    --data '{"symbol":"GOOG", "price":900.00}' \
    http://localhost:9000/parser/json/save/goog
  */
  def saveBodyToFile(path: String, maxLength: Int): Action[Either[MaxSizeExceeded, File]] =
    Action(parse.maxLength(maxLength, saveInFile(path))) { implicit request: Request[Either[MaxSizeExceeded, File]] =>
      Ok("Saved the request content to " + request.body)
    }

  private def forward(request: WSRequest): BodyParser[WSResponse] = BodyParser { req =>
    Accumulator.source[ByteString].mapFuture { source =>
      request.withBody(source).execute().map(Right.apply)
    }
  }

  def streamBody: Action[AnyContent] = Action(forward(wsClient.url(forwardStreamUrl))) {
    Ok(s"Streamed to $forwardStreamUrl")
  }

}
