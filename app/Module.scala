
import com.google.inject.AbstractModule
import controllers.admin.{InMemoryItemDao, ItemDao}
import play.api.libs.concurrent.AkkaGuiceSupport
import services.session.{ClusterSystem, SessionCache}

class Module extends AbstractModule with AkkaGuiceSupport {
  override def configure(): Unit = {
    bind(classOf[ClusterSystem]).asEagerSingleton()
    bindActor[SessionCache]("replicatedCache")

    bind(classOf[ItemDao]).to(classOf[InMemoryItemDao])
  }
}
