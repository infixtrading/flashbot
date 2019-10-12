package flashbot.tools

import scalafx.application.JFXApp
import scalafx.application.JFXApp.PrimaryStage
import scalafx.scene.Scene
import scalafx.scene.layout.{BorderPane, HBox}
import scalafx.scene.paint.Color._
import scalafx.beans.property._
import org.gerweck.scalafx.util._
import scalafx.beans.value.ObservableValue
import scalafx.geometry.{Insets, Pos}
import scalafx.geometry.Orientation.Horizontal
import scalafx.scene.control.{Button, Slider}
import scalafx.scene.shape.Rectangle

import scala.concurrent.duration._
import scala.language.postfixOps

object ChartingWindow extends JFXApp {

  val base = DoubleProperty(15)
  val height = DoubleProperty(10)
  val area = DoubleProperty(0)

  area <== base * height / 2

  def printValues() = println(f"base = ${base()}%4.1f, height = ${height()}%4.1f, area = ${area()}%5.1f\n")
  printValues()

  println("Setting base to 20")
  base() = 20
  printValues()

  println("Setting height to " + 5)
  height() = 5
  printValues()

  val sliderA = new Slider {
    min = 0
    max = 255
    value = 255
    orientation = Horizontal
    alignmentInParent = Pos.BottomRight
  }
  val sliderB = new Slider {
    min = 0
    max = 255
    value = 255
    orientation = Horizontal
    alignmentInParent = Pos.BottomRight
  }

//  val sliderValsA = DoubleProperty(255)
//  sliderValsA <== sliderA.value

  slider.value

  stage = new PrimaryStage {
    width = 800
    height = 600
    title = "Sam Demo"

    scene = new Scene {
      root = new HBox {
        padding = Insets(10)
        children = Seq(
          new Button {
            text = "hello"
            onAction = { ae => println("hi", ae) }
          },
          slider
        )

        sliderVals.map()

      }

//      content = new Rectangle {
//        x = 25
//        y = 40
//        width = 100
//        height = 100
//        fill <== when (hover) choose Green otherwise Red
//        fill.onChange { (source, prev, next) =>
//          println(s"Fill changed to $next")
//        }
//      }
//      root = new BorderPane {
//        padding = Insets(25)
//        center = new Label("Hello SBT")
//      }
    }
  }


}
