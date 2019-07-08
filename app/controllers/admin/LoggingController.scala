package controllers.admin

import javax.inject.Inject
import play.api.Logging
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

class LoggingAction @Inject()(
  parser: BodyParsers.Default)(
  implicit ec: ExecutionContext) extends ActionBuilderImpl(parser) with Logging {

  override def invokeBlock[A](request: Request[A], block: Request[A] => Future[Result]): Future[Result] = {
    logger.info(s"Received request=$request")
    block(request)
  }

}

class LoggingController @Inject()(
  loggingAction: LoggingAction,
  cc: ControllerComponents) extends AbstractController(cc) {

  def log: Action[AnyContent] = loggingAction {
    Ok("Request was logged")
  }

}

