package controllers.admin

import java.util.UUID

import controllers.UserInfoAction
import controllers.admin.ItemRequests.{AddTag, CreateItem}
import controllers.admin.ItemResponses.DeleteResponse
import javax.inject.Inject
import play.api.libs.json.{Format, Json}
import play.api.mvc.Results._
import play.api.mvc._

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

// Take-aways
// - ActionTransformer: Transforms Request => UserRequest
// - ActionRefiner: Refines UserRequest => ItemRequest
// - ActionFilter: Filters ItemRequest based on accessibility

case class UserRequest[A](
  userName: Option[String],
  request: Request[A]) extends WrappedRequest(request)

class UserAction @Inject()(
  override val parser: BodyParsers.Default)(
  override implicit val executionContext: ExecutionContext)
  extends ActionBuilder[UserRequest, AnyContent]
    with ActionTransformer[Request, UserRequest] {

  override protected def transform[A](request: Request[A]): Future[UserRequest[A]] =
    Future.successful(UserRequest(request.session.get("username"), request))
}

case class Item(id: UUID, tags: Set[String])

object Item {
  implicit val ItemFormat: Format[Item] = Json.format[Item]
}

trait ItemDao {

  def create(tags: Set[String])(implicit ec: ExecutionContext): Future[Item]

  def findById(itemId: UUID)(implicit ec: ExecutionContext): Future[Option[Item]]

  def addTag(itemId: UUID, tag: String)(implicit ec: ExecutionContext): Future[Option[Item]]

  def delete(itemId: UUID)(implicit ec: ExecutionContext): Future[Boolean]

  def addAccessibility(itemId: UUID, username: String)(implicit ec: ExecutionContext): Set[String]

  def isAccessibleByUser(itemId: UUID, username: String)(implicit ec: ExecutionContext): Future[Option[Boolean]]

  def removeAccessibility(itemId: UUID, username: String)(implicit ec: ExecutionContext): Set[String]

}

class InMemoryItemDao extends ItemDao {

  private val itemCache = new TrieMap[UUID, Item]()
  private val itemUserCache = new TrieMap[UUID, Set[String]]()

  override def create(tags: Set[String])(implicit ec: ExecutionContext): Future[Item] = {
    Future.successful {
      val item = Item(UUID.randomUUID, tags)
      itemCache += item.id -> item
      item
    }
  }

  override def findById(itemId: UUID)(implicit ec: ExecutionContext): Future[Option[Item]] =
    Future.successful(itemCache.get(itemId))

  override def addTag(itemId: UUID, tag: String)(implicit ec: ExecutionContext): Future[Option[Item]] =
    findById(itemId).map { itemOpt =>
      itemOpt.map { item =>
        val updated = item.copy(tags = item.tags + tag)
        itemCache += itemId -> updated
        updated
      }
    }

  override def delete(itemId: UUID)(implicit ec: ExecutionContext): Future[Boolean] = {
    Future.successful {
      itemCache -= itemId
      itemUserCache -= itemId
      true
    }
  }

  override def addAccessibility(itemId: UUID, username: String)(implicit ec: ExecutionContext): Set[String] = {
    val users = itemUserCache.getOrElse(itemId, Set.empty[String]) + username
    itemUserCache += itemId -> users
    users
  }

  override def isAccessibleByUser(itemId: UUID, username: String)(implicit ec: ExecutionContext): Future[Option[Boolean]] =
    Future.successful(itemUserCache.get(itemId).map(_.contains(username)))

  override def removeAccessibility(itemId: UUID, username: String)(implicit ec: ExecutionContext): Set[String] = {
    val users = itemUserCache.getOrElse(itemId, Set.empty[String]) - username
    itemUserCache += itemId -> users
    users
  }

}

case class ItemRequest[A](
  item: Item,
  request: UserRequest[A]) extends WrappedRequest(request) {
  def username: Option[String] = request.userName
}

object ItemRequests {

  case class CreateItem(tags: Set[String])

  implicit val CreateItemFormat: Format[CreateItem] = Json.format[CreateItem]

  case class AddTag(tag: String)

  implicit val AddTagFormat: Format[AddTag] = Json.format[AddTag]

}

object ItemResponses {

  case class DeleteResponse(success: Boolean)

  implicit val DeleteResponseFormat: Format[DeleteResponse] = Json.format[DeleteResponse]

}

class AuthItemController @Inject()(
  userInfoAction: UserInfoAction,
  userAction: UserAction,
  itemDao: ItemDao,
  cc: ControllerComponents)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  def ItemAction(itemId: UUID)(implicit ec: ExecutionContext): ActionRefiner[UserRequest, ItemRequest] = new ActionRefiner[UserRequest, ItemRequest] {
    override def executionContext: ExecutionContext = ec

    override def refine[A](input: UserRequest[A]): Future[Either[Status, ItemRequest[A]]] =
      itemDao
        .findById(itemId)
        .map {
          case None => Left(NotFound)
          case Some(item) => Right(ItemRequest(item, input))
        }
  }

  def PermissionCheckAction(implicit ec: ExecutionContext): ActionFilter[ItemRequest] = new ActionFilter[ItemRequest] {
    def executionContext: ExecutionContext = ec

    def filter[A](request: ItemRequest[A]): Future[Option[Status]] = {
      request.username.map { username =>
        itemDao.isAccessibleByUser(request.item.id, username).map {
          case None => Some(NotFound)
          case Some(false) => Some(Forbidden)
          case _ => None
        }
      }.getOrElse(Future.successful(Some(Forbidden)))
    }
  }

  /*
  curl -v \
    --cookie "userInfo=eyJhbGciOiJIUzI1NiJ9.eyJkYXRhIjp7Im5vbmNlIjoiNDZiNzRhODlmMDNmMzZmMjgxZTIxZGJlOWY3YzEzOTU1MGRlZWVmYTQzNThiOWM5IiwiYyI6ImEwN2RjZjZkNDY0ZjIyODc2OGRhOGIxMjlmODBjODY5M2NiMjIzYzMxMjQ5NzJhZDYyODk4YmMyMDcxYmZjNDMzNjk1NjA1NDg4YWQwMiJ9LCJleHAiOjE1OTQwMDI5OTYsIm5iZiI6MTU2MjQ2Njk5NiwiaWF0IjoxNTYyNDY2OTk2fQ.Ezp3i0D_b2vysBN5HOS3cMdoQBXGUB3ofalnlLejI2o" \
    --header "Content-type: application/json" \
    --request POST \
    --data '{"tags": ["Tag1", "Tag2"]}' \
    http://localhost:9000/item
   */
  def create: Action[CreateItem] = userInfoAction.async(parse.json[CreateItem]) { implicit request =>
    itemDao
      .create(request.body.tags)
      .map(item =>
        Ok(Json.stringify(Json.toJson(item))).as("application/json"))
  }

  /*
  curl \
  --header "Content-type: application.json" \
  http://localhost:9000/item/UUID
   */
  def findById(itemId: UUID): Action[AnyContent] = userInfoAction.async { implicit request =>
    itemDao
      .findById(itemId)
      .map(itemOpt =>
        Ok(Json.stringify(Json.toJson(itemOpt))).as("application/json"))
  }

  /*
  curl \
  --header "Content-type: application/json" \
  --request PUT \
  --data '{"tag": "Tag 3"}' \
  http://localhost:9000/item/703dc8db-35d5-4dea-814a-bc77aec17fd5
   */
  def addTag(itemId: UUID): Action[AnyContent] =
    userInfoAction
      .andThen(userAction)
      .andThen(ItemAction(itemId))
      .andThen(PermissionCheckAction)
      .async { request =>
        request.request.request.body.asJson
          .map(jsValue => itemDao
            .addTag(request.item.id, jsValue.as[AddTag].tag)
            .map(itemOpt => Ok(Json.stringify(Json.toJson(itemOpt))).as("application/json")))
          .getOrElse(Future.successful(BadRequest("Invalid json")))
      }

  /*
  curl \
  --headers "Content-type: application/json"
  --request DELETE
  http://localhost:9000/UUID
   */
  def delete(itemId: UUID): Action[AnyContent] = Action.async { implicit request =>
    itemDao.delete(itemId).map { isDeleted =>
      Ok(Json.stringify(Json.toJson(DeleteResponse(isDeleted))))
    }
  }

  //  def addAccessibility(itemId: UUID, username: String): Action[AnyContent] = Action.async { implicit request =>
  //    itemDao.addAccessibility(itemId).map { isDeleted =>
  //      Ok(DeleteRe("success" -> isDeleted))
  //    }
  //  }

}


