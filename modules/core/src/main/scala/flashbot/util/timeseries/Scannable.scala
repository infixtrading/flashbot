package flashbot.util.timeseries

import java.time.{Duration, Instant}

import flashbot.models.Candle
import flashbot.util.time._
import flashbot.util.timeseries.Implicits._
import flashbot.util.timeseries.Scannable.BaseFoldable.{Foldable, Reducible}
import org.ta4j.core.{Bar, BaseBar, num}

object Scannable {

  // Type classes
  trait GenEmpty[C] {
    def empty(micros: Long, duration: java.time.Duration, prev: Option[C]): C
  }

  object GenEmpty {
    // Keep the previous bars prices, but overwrite the time to our own, and remove volume,
    // if the previous bar exists.
    implicit def candleHasEmpty: GenEmpty[Candle] = new GenEmpty[Candle] {
      override def empty(micros: Long, duration: java.time.Duration, prev: Option[Candle]): Candle =
        prev.map(_.copy(micros = micros, volume = 0))
          .getOrElse(Candle.empty(micros))
    }

    implicit def barHasEmpty: GenEmpty[Bar] = new GenEmpty[Bar] {
      override def empty(micros: Long, duration: java.time.Duration, prev: Option[Bar]) = {
        val fn = num.DoubleNum.valueOf(0).function()
        val bar = new BaseBar(duration, micros.microsToZdt.plus(duration), fn)

        // Fill in artificial prices for time periods where no data exists
        if (prev.isDefined) bar.addPrice(prev.get.getClosePrice)
        else bar.addPrice(fn(0))
        bar
      }
    }
  }


  sealed trait BaseFoldable[I, C] {
    def fold(candle: C, item: I): C
  }
  object BaseFoldable {
    type Reducible[C] = BaseFoldable[C, C]
    type Foldable[I, C] = BaseFoldable[I, C]

    implicit object PriceSeriesFoldableIntoCandle extends Foldable[(Instant, Double), Candle] {
      override def fold(candle: Candle, item: (Instant, Double)): Candle = candle.mergePrice(item._2)
    }

    implicit object PriceVolSeriesFoldableIntoCandle extends Foldable[(Instant, Double, Double), Candle] {
      override def fold(candle: Candle, item: (Instant, Double, Double)): Candle = candle.mergeTrade(item._2, item._3)
    }

    implicit object CandleFoldableIntoBar extends Foldable[Candle, Bar] {
      override def fold(bar: Bar, candle: Candle) = {
        val candle2bar = bar.buildCandleConverter
        BarIsReducible.fold(bar, candle2bar(candle))
      }
    }

    implicit object BarFoldableIntoCandle extends Foldable[Bar, Candle] {
      override def fold(candle: Candle, bar: Bar) = CandleIsReducible.fold(candle, bar.candle)
    }

    implicit object CandleIsReducible extends Reducible[Candle] {
      override def fold(candle: Candle, item: Candle): Candle = candle.mergeOHLC(item)
    }

    implicit object BarIsReducible extends Reducible[Bar] {
      override def fold(bar: Bar, item: Bar): Bar = {
        val fn = bar.getAmount.function()
        new BaseBar(bar.getTimePeriod,
          bar.getEndTime,
          bar.getOpenPrice,
          bar.getMaxPrice.max(item.getMaxPrice),
          bar.getMinPrice.min(item.getMinPrice),
          item.getClosePrice,
          bar.getVolume.plus(item.getVolume),
          fn(0)
        )
      }
    }

//    implicit def priceSeriesFoldIntoCandle: Foldable[(Instant, Double), Candle] =
//      new Foldable[(Instant, Double), Candle] {
//      }

//    implicit def priceVolSeriesFoldIntoCandle: Foldable[(Instant, Double, Double), Candle] =
//      new Foldable[(Instant, Double, Double), Candle] {
//      }

    implicit def reducibleIsFoldable[C](implicit reducible: Reducible[C]): Foldable[C, C] = new Foldable[C, C] {
      override def fold(candle: C, item: C) = reducible.fold(candle, item)
    }
  }


  sealed trait BaseScannable[I, C] extends Foldable[I, C] with GenEmpty[C]
  object BaseScannable {
    type ScannableItem[I, C] = BaseScannable[I, C]
    type Scannable[C] = BaseScannable[C, C]

    implicit def scannableItem[I, C](implicit foldableItem: Foldable[I, C],
                                     genEmptyCandle: GenEmpty[C]): ScannableItem[I, C] =
      new ScannableItem[I, C] {
        override def fold(candle: C, item: I): C = foldableItem.fold(candle, item)
        override def empty(micros: Long, duration: java.time.Duration, prev: Option[C]): C =
          genEmptyCandle.empty(micros, duration, prev)
      }

    implicit def scannable[C](implicit reducible: Reducible[C],
                              genEmpty: GenEmpty[C]): Scannable[C] = new Scannable[C] {
      override def fold(candle: C, item: C): C = reducible.fold(candle, item)
      override def empty(micros: Long, duration: java.time.Duration, prev: Option[C]): C =
        genEmpty.empty(micros, duration, prev)
    }
  }

//  trait BaseScannable[I, C] { self: Foldable[I, C] with GenEmpty[C] => }
//    extends Foldable[I, C] with GenEmpty[C]

//  trait ScannableItem[I, C] extends Foldable[I, C] with GenEmpty[C]
//  trait ScannableItem[I, C] {
//    def fold(candle: C, item: I): C
//    def empty(micros: Long, prev: Option[C]): C
//  }




//  trait Scannable[C] { self: BaseScannable[C, C] => }
//  trait Scannable[C] {
//    def fold(candle: C, item: C): C
//    def empty(micros: Long, prev: Option[C]): C
//  }

//  object Scannable {
//  }




  // Implicits


}

