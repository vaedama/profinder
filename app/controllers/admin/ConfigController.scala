package controllers.admin

import com.typesafe.config.Config
import javax.inject.{Inject, Singleton}
import play.api.libs.json.{Format, JsValue, Json, JsonValidationError}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import play.api.{ConfigLoader, Configuration}

import scala.jdk.CollectionConverters._

@Singleton class ConfigController @Inject()(
  configuration: Configuration,
  cc: ControllerComponents) extends AbstractController(cc) {

  implicit val configLoader: ConfigLoader[AppConfig] = (config: Config, path: String) => {
    val clusterConfig: ClusterConfig = ClusterConfig(
      ports = config.getConfig(path).getIntList("services.session.cluster.ports").asScala.toList.map(_.toInt)
    )

    AppConfig(
      services = ServicesConfig(
        session = SessionConfig(clusterConfig)))
  }

  def config: AppConfig = configuration.get[AppConfig]("app.config")

  def configJsValue: JsValue = Json.toJson(config)

  def get: Action[AnyContent] = Action { implicit request =>
    Ok(Json.toJson(config)).as("application/json")
  }

  def getAtPath(path: String): Action[AnyContent] = Action { implicit request =>
    def loop(paths: List[String], root: Either[JsonValidationError, JsValue]): Either[JsonValidationError, JsValue] = {
      root.fold(
        jsErr => Left(jsErr),
        jsVal =>
          paths match {
            case Nil => Right(jsVal)
            case h :: t => loop(t, (jsVal \ h).toEither)
          }
      )
    }

    loop(path.split('.').toList, Right(configJsValue)).fold(
      jsErr => Ok(Json.toJson(jsErr.messages)),
      jsVal => Ok(jsVal).as("application/json")
    )
  }

}

final case class AppConfig(services: ServicesConfig)

object AppConfig {
  implicit val AppConfigFmt: Format[AppConfig] = Json.format[AppConfig]
}

final case class ServicesConfig(session: SessionConfig)

object ServicesConfig {
  implicit val ServicesConfigFmt: Format[ServicesConfig] = Json.format[ServicesConfig]
}

final case class SessionConfig(cluster: ClusterConfig)

object SessionConfig {
  implicit val SessionConfigFmt: Format[SessionConfig] = Json.format[SessionConfig]
}

final case class ClusterConfig(ports: List[Int])

object ClusterConfig {
  implicit val ClusterConfigFmt: Format[ClusterConfig] = Json.format[ClusterConfig]
}