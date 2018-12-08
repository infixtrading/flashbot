package com.infixtrading.flashbot.core

import java.io.File

import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.stream.Materializer
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.Config
import javax.inject.{Inject, Singleton}
import play.api.Application
import play.api.inject.ApplicationLifecycle
import com.infixtrading.flashbot.engine.TradingEngine
import com.infixtrading.flashbot.util.stream.buildMaterializer

import scala.concurrent.{ExecutionContext, Future, SyncVar}
import scala.concurrent.duration._
import scala.collection.JavaConverters._

/**
  * We can't trust Guice dependency injection of Singletons happening only once.
  * Going the paranoid option.
  */
object Control {

  println("hi")

  val appStarted = new SyncVar[Boolean]
  appStarted.put(false)

  val engine = new SyncVar[ActorRef]
  val dataServer = new SyncVar[ActorRef]

  val tmpDataDir = new SyncVar[File]

  def start()(implicit config: Config, app: Application, system: ActorSystem): Unit = {

    println("Starting")

    // Warn if the app is already started.
    if (appStarted.take()) {
      println("Warning: App already started")
    } else println("Starting App")

    println("a")

    appStarted.put(true)
  }

  def stop(): Unit = {
    if (!appStarted.take()) {
      println("Warning: App already stopped")
    }

    println("b")

//    if (engine.isSet) {
//      engine.take() ! PoisonPill
//    }
//
//    if (dataServer.isSet) {
//      dataServer.take() ! PoisonPill
//    }

    appStarted.put(false)
  }

//  implicit val timeout: Timeout = Timeout(5 seconds)
//  def request[T <: TradingEngine.Response](query: TradingEngine.Query)
//                                          (implicit ec: ExecutionContext): Future[T] =
//    (engine.get ? query).flatMap {
//      case err: TradingEngine.EngineError => Future.failed(err)
//      case err: Throwable => Future.failed(err)
//      case rsp: T => Future.successful(rsp)
//      case rsp => Future.failed(
//        new RuntimeException(s"Request type error for query $query. Unexpected response $rsp."))
//    }
}

/**
  * Idempotent collector of of DI objects. This just feeds data to our thread safe Control object.
  * Must be idempotent to handle the infuriating case where "Singletons" are instantiated twice.
  */
@Singleton
class Control @Inject()() (implicit config: Config,
                           lifecycleState: ApplicationLifecycle,
                           system: ActorSystem,
                           ec: ExecutionContext,
                           app: Application) {
  Control.start()
  lifecycleState.addStopHook(() => {
    Control.stop()
    Future.successful(true)
  })
}
