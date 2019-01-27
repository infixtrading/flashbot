package flashbot.core

import flashbot.engine.TradingSession
import flashbot.core.DeltaFmtJson

import scala.reflect.ClassTag

/**
  * Bots can use the operations defined here to store domain specific state that persists between
  * session restarts. Custom type class instances can be defined if needed, though idk...
  *
  * All persisted state is able to be paused/rewinded through the UI as far back as the persistent
  * journaling will allow.
  */
object State {

  // Vars are in-memory only.
  case class Var[T](key: String, value: T) {
    def map(fn: T => T): Var[T] = copy(value = fn(value))
    def withVal(t: T): Var[T] = copy(value = value)
  }

  trait Get[T] {
    def get(key: String)
           (implicit ctx: TradingSession, buffer: VarBuffer, fmt: DeltaFmtJson[T],
            ct: ClassTag[T]): Option[Var[T]]
  }

  object Get {
    // TC variety 1
    // def series[T](key: String)(implicit ctx: TradingSession, tc: Get[T]) = tc.series(key)

    // TC variety 2
    // def series[T: Get](key: String)(implicit ctx: TradingSession) = implicitly[Get[T]].series(key)

    // TC variety 3. Apply with type params.
    // A trick/convention to pull out the type class from implicits.
    def apply[T](implicit tc: Get[T]): Get[T] = tc

    // Variety 3 usage
    def get[T: Get](key: String)
                                   (implicit ctx: TradingSession, buffer: VarBuffer,
                                    fmt: DeltaFmtJson[T], ct: ClassTag[T]): Option[Var[T]] =
      Get[T].get(key)
  }


  trait Delete[T] {
    def delete(t: Var[T])(implicit ctx: TradingSession, buffer: VarBuffer): Unit
    def delete(x: String)(implicit ctx: TradingSession, buffer: VarBuffer): Unit
  }
  object Delete {
    def apply[T: Delete](implicit tc: Delete[T]): Delete[T] = tc
  }


  trait Update[T] {
    def update(x: Var[T], fn: T => Option[T])
              (implicit ctx: TradingSession, buffer: VarBuffer,
               fmt: DeltaFmtJson[T], ct: ClassTag[T]): Option[Var[T]]
  }
  object Update {
    def apply[T : Update](implicit tc: Update[T], ct: ClassTag[T]): Update[T] = tc
  }


  trait Set[T] {
    def set(x: String, value: T)(implicit ctx: TradingSession, buffer: VarBuffer,
                                 fmt: DeltaFmtJson[T], ct: ClassTag[T]): Var[T]
    def set(x: Var[T], value: T)(implicit ctx: TradingSession, buffer: VarBuffer,
                                 fmt: DeltaFmtJson[T], ct: ClassTag[T]): Var[T]
    def setDefault(x: String, value: T)(implicit ctx: TradingSession, buffer: VarBuffer,
                                        fmt: DeltaFmtJson[T], ct: ClassTag[T]): Var[T]
  }
  object Set {
    def apply[T : Set](implicit tc: Set[T], ct: ClassTag[T]): Set[T] = tc
  }


  object ops {

    // Define an implicit class for implicit conversions.
    // This is not necessary for Get, but it is for Update.
    // Also, make it a value class to avoid runtime overhead:
    //   1. Make the single parameter a val
    //   2. Extend AnyVal
    implicit class UpdateOps[T](val x: Var[T]) extends AnyVal {
      def update(fn: T => Option[T])
                (implicit ctx: TradingSession, buffer: VarBuffer, fmt: DeltaFmtJson[T],
                 ct: ClassTag[T]): Option[Var[T]] =
        Update[T].update(x, fn)
    }

    implicit class DeleteOps[T](val x: Var[T]) extends AnyVal {
      def delete(implicit ctx: TradingSession, buffer: VarBuffer): Unit =
        Delete[T].delete(x)
    }

    implicit class SetOps[T](val x: String) extends AnyVal {
      def set(value: T)(implicit ctx: TradingSession, buffer: VarBuffer,
                        fmt: DeltaFmtJson[T], ct: ClassTag[T]): Var[T] =
        Set[T].set(x, value)

      def setDefault(value: T)(implicit ctx: TradingSession, buffer: VarBuffer,
                               fmt: DeltaFmtJson[T], ct: ClassTag[T]): Var[T] =
        Set[T].set(x, value)
    }

    implicit class SetOps2[T](val x: Var[T]) extends AnyVal {
      def set(value: T)(implicit ctx: TradingSession, buffer: VarBuffer,
                        fmt: DeltaFmtJson[T], ct: ClassTag[T]): Var[T] =
        Set[T].set(x, value)
    }

    // Actually we'll add a "series" operation on String.
    implicit class GetOps(val x: String) extends AnyVal {
      def get[T : Get](implicit ctx: TradingSession, buffer: VarBuffer,
                       fmt: DeltaFmtJson[T], ct: ClassTag[T]): Option[Var[T]] =
        Get[T].get(x)
    }

    implicit def tcGet[T]: Get[T] = new Get[T] {
      def get(key: String)(implicit ctx: TradingSession, buffer: VarBuffer,
                           fmt: DeltaFmtJson[T], ct: ClassTag[T]): Option[Var[T]] =
        buffer.get[T](key)
    }

    implicit def tcSet[T]: Set[T] = new Set[T] {
      def set(key: String, value: T)
             (implicit ctx: TradingSession, buffer: VarBuffer,
              fmt: DeltaFmtJson[T], ct: ClassTag[T]): Var[T] =
        buffer.set[T](key, value)

      def set(x: Var[T], value: T)
             (implicit ctx: TradingSession, buffer: VarBuffer,
              fmt: DeltaFmtJson[T], ct: ClassTag[T]): Var[T] =
        buffer.set[T](x.key, value)

      def setDefault(key: String, value: T)
                    (implicit ctx: TradingSession, buffer: VarBuffer,
                     fmt: DeltaFmtJson[T], ct: ClassTag[T]): Var[T] = {
        val existing = buffer.get[T](key)
        if (existing.isDefined) existing.get
        else buffer.set[T](key, value)
      }
    }

    implicit def tcUpdate[T]: Update[T] = new Update[T] {
      def update(x: Var[T], fn: T => Option[T])
                (implicit ctx: TradingSession, buffer: VarBuffer,
                 fmt: DeltaFmtJson[T], ct: ClassTag[T]): Option[Var[T]] = {
        // Get the var, to ensure it's a valid reference.
        val v = buffer.get[T](x.key)
        // Run the update fn and set it, or delete if None.
        val result = fn(v.get.value)
        if (result.isDefined)
          Some(buffer.set[T](x.key, result.get))
        else {
          buffer.delete(x.key)
          None
        }
      }
    }

    implicit def tcDelete[T]: Delete[T] = new Delete[T] {
      def delete(t: Var[T])(implicit ctx: TradingSession, buffer: VarBuffer): Unit =
        buffer.delete(t.key)

      def delete(x: String)(implicit ctx: TradingSession, buffer: VarBuffer): Unit =
        buffer.delete(x)
    }

  }

}
