//package flashbot.core
//
//import flashbot.models.core.Market
//import io.circe.{Decoder, Encoder}
//
//import scala.language.implicitConversions
//
//trait SchemaParam[T, V] extends Any {
//  def schema(implicit loader: SessionLoader): json.Schema[T]
//  def value: V
//}
//
//object SchemaParam {
//  implicit def getValue[T, V](sp: SchemaParam[T, V]): V = sp.value
//  implicit def encoder[T, V : Encoder]: Encoder[SchemaParam[T, V]] =
//    implicitly[Encoder[V]].contramap(_.value)
//}
//
//
//class ExchangeParam(val value: String) extends AnyVal with SchemaParam[ExchangeParam, String] {
//  override implicit def schema(implicit loader: SessionLoader) =
//    json.Schema.enum(loader.exchanges)
//}
//
//object ExchangeParam {
//  implicit def decoder: Decoder[ExchangeParam] =
//    Decoder.decodeString.map(x => new ExchangeParam(x))
//  implicit def encoder: Encoder[ExchangeParam] =
//    Encoder.encodeString.contramap(_.value)
//  implicit def build(str: String): ExchangeParam = new ExchangeParam(str)
//}
//
//
//class MarketParam(val value: Market) extends AnyVal with SchemaParam[MarketParam, Market] {
//  override implicit def schema(implicit loader: SessionLoader) = ???
//}
//
//object MarketParam {
//  implicit def decoder: Decoder[MarketParam] =
//    implicitly[Decoder[Market]].map(x => new MarketParam(x))
//  implicit def encoder: Encoder[MarketParam] =
//    implicitly[Encoder[Market]].contramap(_.value)
//  implicit def build(v: Market): MarketParam = new MarketParam(v)
//  implicit def build(v: String): MarketParam = new MarketParam(v)
//}
//
//
//class DataTypeParam(val value: String) extends AnyVal with SchemaParam[DataTypeParam, String] {
//  override implicit def schema(implicit loader: SessionLoader) = ???
//}
//
//object DataTypeParam {
//  implicit def decoder: Decoder[DataTypeParam] =
//    Decoder.decodeString.map(x => new DataTypeParam(x))
//  implicit def encoder: Encoder[DataTypeParam] =
//    Encoder.encodeString.contramap(_.value)
//  implicit def build(v: String): DataTypeParam = new DataTypeParam(v)
//}
//
