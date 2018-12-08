import com.google.inject.AbstractModule
import com.infixtrading.flashbot.core.Control

class Module extends AbstractModule {
  override def configure() = {
    bind(classOf[Control]).asEagerSingleton()
  }
}
