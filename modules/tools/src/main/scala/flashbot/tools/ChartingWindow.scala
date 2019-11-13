package flashbot.tools

import java.awt.Dimension
import java.awt.event.{KeyEvent, KeyListener}

import flashbot.tools.OrderBookScanner.{chart, plot}
import org.jfree.chart.ChartFrame
import org.jfree.chart.event.{PlotChangeEvent, PlotChangeListener}
import org.jfree.chart.plot.XYPlot

import scala.swing.{Frame, Publisher}

class ChartingWindow(title: String) {

  var frame: Option[Frame] = None

  def reload: Unit = {

    if (frame.nonEmpty) {
      frame.get.visible = false;
      frame.get.dispose()
      frame = None
    }

    class PriceFrame extends Frame with Publisher {
      override lazy val peer = new ChartFrame(title, chart.peer, true) with InterfaceMixin
      peer.getChartPanel.setRangeZoomable(false)
      var zoomListener: PlotChangeListener = _

      def resetZoomListener(): Unit = {
        zoomListener = new PlotChangeListener {
          override def plotChanged(event: PlotChangeEvent): Unit = {
            plot.removeChangeListener(zoomListener)
            plot.getSubplots.forEach {
              case p: XYPlot =>
                for (i <- 0 until p.getRangeAxisCount) {
                  val axis = p.getRangeAxis(i)
                  axis.setRangeWithMargins(p.getDataRange(axis))
                }
              case _ =>
            }
            resetZoomListener()
          }
        }
        plot.addChangeListener(zoomListener)
      }

      resetZoomListener()

      peer.addKeyListener(new KeyListener {
        override def keyTyped(e: KeyEvent): Unit = {
          // Space
          if (e.getExtendedKeyCode == 32) {
            println("Pause")
          }
        }

        override def keyPressed(e: KeyEvent): Unit = {}

        override def keyReleased(e: KeyEvent): Unit = {}
      })
    }

    frame = Some(new PriceFrame)
    frame.get.size = new Dimension(1100, 600)
    frame.get.visible = true
  }


}

object ChartingWindow {
  case class ChartConfig()
}
