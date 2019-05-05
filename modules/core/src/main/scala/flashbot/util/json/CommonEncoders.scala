package flashbot.util.json

import io.circe.{Decoder, Encoder, JsonObject, KeyDecoder, KeyEncoder}
import io.circe.syntax._

import scala.reflect.ClassTag

object CommonEncoders {

  implicit def deboxBufferDe[T: Decoder:ClassTag]: Decoder[debox.Buffer[T]] =
    Decoder.decodeSeq(implicitly[Decoder[T]]).map(debox.Buffer.fromIterable)

  implicit def deboxBufferEn[T: Encoder:ClassTag]: Encoder[debox.Buffer[T]] =
    Encoder.encodeSeq(implicitly[Encoder[T]]).contramapArray(_.toIterable.toSeq)

  implicit def deboxMapDe[K: KeyDecoder:ClassTag, V: Decoder:ClassTag]: Decoder[debox.Map[K, V]] =
    Decoder.decodeJsonObject.map { o =>
      val kd = implicitly[KeyDecoder[K]]
      val dv = implicitly[Decoder[V]]
      val map = debox.Map.empty[K, V]
      for (key <- o.keys) {
        val k = kd(key).get
        map(k) = o(key).get.as[V].right.get
      }
      map
    }

  implicit def deboxMapEn[K: KeyEncoder, V: Encoder]: Encoder[debox.Map[K, V]] =
    Encoder.encodeJsonObject.contramapObject { map =>
      var o = JsonObject()
      val ke = implicitly[KeyEncoder[K]]
      map.foreachKey { key =>
        o = o.add(ke(key), map(key).asJson)
      }
      o
    }

//  implicit def objArrayFIFOEncoder[T: Encoder]: Encoder[ObjectArrayFIFOQueue[T]] =
//    Encoder.encodeList.contramapArray { fifo =>
//      val buf = ListBuffer[T]()
//      ???
//    }
//
//  implicit def objArrayFIFODecoder[T: Decoder]: Decoder[ObjectArrayFIFOQueue[T]] = {
//    Decoder.decodeList[T].map { list =>
//      val fifo = new ObjectArrayFIFOQueue[T](list.size)
//      list.foreach(fifo.enqueue)
//      fifo
//    }
//  }



}
