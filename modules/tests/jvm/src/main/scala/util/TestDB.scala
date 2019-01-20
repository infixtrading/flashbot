package util

import akka.stream.alpakka.slick.javadsl.SlickSession
import com.infixtrading.flashbot.core.FlashbotConfig
import com.infixtrading.flashbot.db.Tables

import scala.concurrent.Future

object TestDB {
  def dropTestDB()(implicit config: FlashbotConfig): Future[Unit] = {
    val slickSession = SlickSession.forConfig(config.db)
    import slickSession.profile.api._
    val schema = Tables.map(_.schema).reduce(_ ++ _)
    slickSession.db.run(schema.drop)
  }
}
