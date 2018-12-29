# Flashbot
[![Build Status](https://travis-ci.org/infixtrading/flashbot.svg?branch=master)](https://travis-ci.org/infixtrading/flashbot)

Flashbot is a "batteries included" Java/Scala library for building, simulating, and deploying HFT trading strategies on Cryptocurrency exchanges. It provides server components for easily building a cluster of market data servers that efficiently stream data to Flashbot strategies which compute algorithms and execute or simulate orders.

It's intended as a framework for building trading systems that operate at microsecond precision.

## Components
Flashbot fully embraces Akka for persistence, streaming, clustering, fault-tolerance, and concurrency control. [Akka Cluster 2.5.x](https://doc.akka.io/docs/akka/2.5/index-cluster.html) is a requirement.

### `TradingEngine`
The `TradingEngine` actor is the main class for running Flashbot. Starting a new trading engine is as simple as:
```scala
// Start the Akka system
val system = ActorSystem("trading-system", FlashbotConfig.load.akka)

// Load the trading engine
val engine = system.actorOf(TradingEngine.props("my-engine"))
```

You can now send requests to the engine:
```scala
implicit val timeout = Timeout(5 seconds)
val pong = Await.result(engine ? Ping, timeout.duration) // = Pong(<timestamp>)
```

The typical use case is to send strategy backtest requests to the engine, as well as to manage running bots by configuring, starting, and stopping them in either "live" or "paper" mode.

[Read the source](https://github.com/infixtrading/flashbot/blob/master/modules/server/src/main/scala/com/infixtrading/flashbot/engine/TradingEngine.scala) for a full overview of the supported commands.

### `DataServer`
A `DataServer` is an actor that responds to market data requests. There is always one data server that is local to a TradingEngine (one will be created by default if not supplied) and there may also be any number of remote `DataServer` actors in the cluster that provide additional data sets.

[Read the source](https://github.com/infixtrading/flashbot/blob/master/modules/server/src/main/scala/com/infixtrading/flashbot/engine/DataServer.scala) for to get an idea of how it runs.

### `Strategy`
A `Strategy` is a class that contains the logic of a trading algorithm. By definition, it does not know if it's running in backtest, paper trading, or live trading mode.

[Read the source](https://github.com/infixtrading/flashbot/blob/master/modules/tests/jvm/src/main/scala/strategies/LookAheadCandleStrategy.scala) of an example strategy that is used in our unit tests. This is a strategy that sources itself with a randomly generated data stream of OHLC data and "cheats" by looking ahead by one time step to make a profitable trade. This is used in testing to ensure that a strategy with perfect information of the future never makes an unprofitable trade.

[Read the source](https://github.com/infixtrading/flashbot/blob/master/modules/server/src/main/scala/com/infixtrading/flashbot/engine/TradingSessionActor.scala) of the `TradingSessionActor` to fully understand how a `TradingSession` streams data to a `Strategy` and how a `Strategy` interacts with the session.

## Configuration
Check out the default [configuration file](https://github.com/infixtrading/flashbot/blob/master/modules/server/src/main/resources/reference.conf) to see a list of available options. You can override any of the settings in your own `application.conf` file.

## Status
This project is a WIP. In it's current state it's a good tool for prototyping and simulating HFT strategies. However, this is alpha software. The unit tests are minimal. Do not use it out of the box for real trading yet! You have been warned.

The primary API is for Scala. At this point, Java users may find some methods are unavailable for strategy building. Providing first class Java support is a priority and is in active development.
