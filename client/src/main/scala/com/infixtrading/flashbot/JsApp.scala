package com.infixtrading.flashbot

import java.net.URI

import io.circe.Json
import io.circe.parser._
import org.scalajs.dom
import slinky.web.ReactDOM

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

object JSApp {

//  def paramsJson: Json = {
//    val uri = new URI(dom.window.location.href)
//    val kvs: Seq[String] = uri.getQuery.split("&")
//    val body = kvs.map(kvStr => kvStr.split("=").toList match {
//      case k :: v :: Nil => s""" "$k": $v """
//    }).mkString(", ")
//    val jsonStr = s"{$body}"
//    println(jsonStr)
//    parse(jsonStr).right.get
//  }

  def main(args: Array[String]): Unit = {

    println("Hi from scala")

//    val reportRoot = Option(dom.document.getElementById("report"))

    //    if (reportRoot.isDefined) {
    //      val reportView = ReportView(name = BacktestSetup.strategy, params = paramsJson)
    //      ReactDOM.render(reportView, reportRoot.get)
    //    }

//    val backtestSetupRoot = Option(dom.document.getElementById("BacktestSetup"))
//    if (backtestSetupRoot.isDefined) {
//      val configFormRoot = Option(dom.document.getElementById("BacktestConfigForm"))
//      val foo = BacktestConfigForm(foo = "hello")
//      ReactDOM.render(foo, configFormRoot.get)
//    }
  }
}
