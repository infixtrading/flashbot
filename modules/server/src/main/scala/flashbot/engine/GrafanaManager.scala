package flashbot.engine

import akka.actor.Actor
import com.appnexus.grafana.client._
import com.appnexus.grafana.client.models.DashboardRow
import com.appnexus.grafana.configuration.GrafanaConfiguration

class GrafanaManager extends Actor {
//  val client = new GrafanaClient()
  override def receive = {
    case _ =>
//      client.updateDashboard()
//      val dashboard = client.getDashboard("foo")
//      val d = dashboard.dashboard()
//      val dr: DashboardRow = d.rows().get(0)
//      dr.panels()
//      val panel = dr.panels().get(0)
//      panel.editable()
//
//      dashboard.dashboard(d)

  }
}
