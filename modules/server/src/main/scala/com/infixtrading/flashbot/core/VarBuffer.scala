package com.infixtrading.flashbot.core

import io.circe.{Decoder, Encoder}

import scala.reflect.{ClassTag, classTag}
import com.infixtrading.flashbot.core.State.Var
import com.infixtrading.flashbot.core.VarBuffer.VarState
import com.infixtrading.flashbot.engine.TradingSession
import com.infixtrading.flashbot.engine.TradingSession._
import com.infixtrading.flashbot.models.api.LogMessage
import com.infixtrading.flashbot.report.ReportDelta._
import com.infixtrading.flashbot.report.ReportEvent._

import scala.collection.mutable

/**
  * A stateful class that keeps track of in-memory internal state for a strategy. Currently used
  * by the [[State]] operations to keep track of Vars. It is instantiated with the initial report
  * used for the trading session. This is for loading vars into memory that exist in the report
  * from a previous run of the bot.
  */
class VarBuffer(initialReportVals: Map[String, Any]) {
  import com.infixtrading.flashbot.core.VarBuffer._

  val vars = mutable.Map.empty[String, VarState]

  /**
    * Set and load a var. Update session. Handle errors.
    */
  def set[T : ClassTag](key: String, value: T)
                       (implicit ctx: TradingSession, fmt: DeltaFmtJson[T]): Var[T] = {

    vars.get(key) match {
      /**
        * Found a variable of the same name and type. Persist to the session. It is up to the
        * type about how this is handled. By default we just send over a PutValueEvent event,
        * however some types may want to persist themselves incrementally. In that case, they
        * will generate and send a sequence of UpdateValueEvent instead.
        */
      case Some(vState: Loaded[T])
          if classTag[T].runtimeClass.isInstance(vState.instance.value) =>
        val newVar = initVar(key, value)
        persistVar(newVar, Some(vState.instance.value))
        newVar

      /**
        * Buffer type doesn't match our type for the same name. Throw error. No persist for u.
        */
      case Some(vState: Loaded[_]) =>
        throw new RuntimeException(classErrorMsg(classTag[T], getClassTag(vState.instance)))

      /**
        * Found a tombstone, meaning this is a fresh var. Initialize, persist, return.
        */
      case Some(Tombstone) =>
        val newVar = initVar(key, value)
        persistVar(newVar, None)
        newVar

      /**
        * Cache miss. Check the state of the session.
        */
      case None =>
        initialReportVals.get(key) match {
          /**
            * Found a value of the same name and type that hasn't been loaded into the buffer
            * yet. Check type, initialize the var and persist.
            */
          case Some(existing: T)
              if classTag[T].runtimeClass.isInstance(existing) =>
            val newVar = initVar(key, value)
            persistVar(newVar, Some(existing))
            newVar

          /**
            * Found a value of a different type. Warn that we are replacing it, but proceed
            * with the operation.
            */
          case Some(existing) =>
            warn(s"Warning: Failed to load session value $key (${getClassTag(existing)}) " +
              s"into class ${classTag[T]}. Replacing existing var.")
            val newVar = initVar(key, value)
            persistVar(newVar, None)
            newVar

          /**
            * This variable is brand new. Initialize and persist.
            */
          case None =>
            val newVar = initVar(key, value)
            persistVar(newVar, None)
            newVar
        }
    }
  }

  /**
    * Delete the var, no matter the type. Remove from session and from buffer.
    */
  def delete(key: String)(implicit ctx: TradingSession): Unit = {
    sendValEvents(RemoveValueEvent(key))
    vars - key
  }

  /**
    * Get a Var from the buffer. Fallback to the session. Handle errors.
    */
  def get[T : ClassTag](key: String)(implicit ctx: TradingSession): Option[Var[T]] = {
    vars.get(key) match {
      /**
        * Found what we're looking for, return.
        */
      case Some(vState: Loaded[T])
          if classTag[T].runtimeClass.isInstance(vState.instance.value) =>
        Some(vState.instance)

      /**
        * Type error
        */
      case Some(vState: Loaded[_]) =>
        throw new RuntimeException(classErrorMsg(classTag[T], getClassTag(vState)))


      /**
        * Found a tombtone. Return None. Don't check the session.
        */
      case Some(Tombstone) => None

      /**
        * Found nothing in the buffer. Look up in the session instead.
        */
      case None =>
        initialReportVals.get(key) match {
          /**
            * Found a var that's in the session but not the buffer. Load it to buffer and return.
            * This should only happen once per var per session, because after the initial one,
            * the typed vars are loaded into the buffer.
            */
          case Some(existing: T)
              if classTag[T].runtimeClass.isInstance(existing) =>
            Some(initVar(key, existing))

          /**
            * Found a value of the wrong type. Warn and return None. Also set a tombstone so that
            * future calls can return None without checking the session.
            */
          case Some(existing) =>
            warn(s"Warning: Failed to decode session value $key (${getClassTag(existing)}) " +
              s"into class ${classTag[T]}. Returning None.")
            vars += (key -> Tombstone)
            None

          /**
            * Not found.
            */
          case None => None
        }

    }
  }

  def persistVar[T](current: Var[T], prev: Option[T])
                   (implicit ctx: TradingSession, fmt: DeltaFmtJson[T]): Unit = {
    if (prev.isDefined) {
      val delta = fmt.diff(prev.get, current.value)
      sendValEvents(UpdateValueEvent(current.key, fmt.deltaEn(delta)))
    } else {
      sendValEvents(PutValueEvent(current.key, fmt.fmtName, fmt.modelEn(current.value)))
    }
  }

  /**
    * Initialize a new var and load into buffer.
    */
  def initVar[T](key: String, value: T): Var[T] = {
    val v = Var(key, value)
    vars += (key -> Loaded(v))
    v
  }

  def warn(msg: String)(implicit ctx: TradingSession): Unit = {
    println(msg)
    ctx.send(LogMessage(msg))
  }
}

object VarBuffer {
//  def getTypeTag[T: TypeTag](obj: T) = typeTag[T]
  def getClassTag[T: ClassTag](obj: T): ClassTag[T] = classTag[T]
  def classErrorMsg(expected: ClassTag[_], actual: ClassTag[_]): String =
    s"Strategy Type Error. Expected class $expected. Got class $actual"
//  def typeErrorMsg(expected: WeakTypeTag[_], actual: TypeTag[_]) =
//    s"Strategy Type Error. Expected $expected. Got $actual"

  sealed trait VarState
  case object Tombstone extends VarState
  case class Loaded[T](instance: Var[T]) extends VarState

  def sendValEvents(valEvents: ValueEvent*)(implicit ctx: TradingSession) = {
    ctx.send(valEvents.map(ReportValueEvent):_*)
  }
}
