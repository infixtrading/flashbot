case class Market(exchange: String, symbol: String) extends Labelled {
  override def toString = s"$exchange.$symbol"
  override def label = {
    val ex = exchange.capitalize
    val sym = CurrencyPair.parse(symbol).map(_.label).getOrElse(symbol)
    s"$ex: $sym"
  }

  def settlementAccount(implicit instruments: InstrumentIndex): Account =
    Account(exchange, instruments(this).settledIn.get)

  def securityAccount(implicit instruments: InstrumentIndex): Account =
    Account(exchange, instruments(this).security.get)

  def baseAccount(implicit instruments: InstrumentIndex): Account =
    Account(exchange, instruments(this).base)

  def quoteAccount(implicit instruments: InstrumentIndex): Account =
    Account(exchange, instruments(this).quote)

  def path[T](dataType: DataType[T]): DataPath[T] =
    DataPath(exchange, symbol, dataType)
}
