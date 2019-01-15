# Flashbot
[![Build Status](https://travis-ci.org/infixtrading/flashbot.svg?branch=master)](https://travis-ci.org/infixtrading/flashbot)

Flashbot is a Java and Scala library for building, simulating, and running low-latency Cryptocurrency trading strategies as a cluster of trading engines and market data servers. A main focus of this library is to make it easier to go from backtest to live strategy.

## Overview
Market data servers (the `DataServer` actor) connect to outside data sources such as exchange websockets or 3rd party signals. They persist and serve that data. Here's a list of features:

  1. Pluggable data sources (supports market data for other financial markets too)
  2. Publish data streams (live, historical, or historical+live) to subscribers
  3. Works with any JDBC compatible database
  4. Delta compression for efficient streaming and storage of order books
  5. Data retention policies
  6. Tolerant to network and websocket failures (i.e. it manages the retries)

The other main actor is the `TradingEngine`. It handles things such as:

  1. Pluggable strategies
  2. Running backtests on data in the cluster
  3. Managing bots that run in live or paper mode
  4. Fault tolerance for strategy exceptions (bots are restored if they crash)
  5. Backtests simulate fees and network latency
  6. Currency conversions

## Docs

  1. Read the Getting Started page
  2. Follow along to some tutorials and examples:
    a. Tutorial 1
    b. Tutorial 2
  3. Read the FAQs

## Status
This project is a WIP. In it's current state it's a good tool for prototyping and simulating strategies. However, this is alpha software. The unit tests are minimal. Do not use it out of the box for real trading yet! You have been warned.

The actor APIs support both Java and Scala, however extensions such as custom exchanges, data sources, and strategies currently require Scala. Full Java support is planned for future releases.

## Configuration
Check out the default [configuration file](https://github.com/infixtrading/flashbot/blob/master/modules/server/src/main/resources/reference.conf) to see a list of available options. You can override any of the settings in your own `application.conf` file.

## Components
Flashbot fully embraces Akka for persistence, streaming, clustering, fault-tolerance, and concurrency control. [Akka Cluster 2.5.x](https://doc.akka.io/docs/akka/2.5/index-cluster.html) is a dependency, but running a cluster is not required. You may use Flashbot in standalone mode on a non-cluster actor system.

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

### `TradingSession`
A `TradingSession` is the container in which a strategy runs. It is used for both backtests and live/paper trading.

[Read the source](https://github.com/infixtrading/flashbot/blob/master/modules/server/src/main/scala/com/infixtrading/flashbot/engine/TradingSessionActor.scala) of the `TradingSessionActor` to fully understand how a `TradingSession` streams data to a `Strategy` and how a `Strategy` interacts with the session.


### `DataServer`
A `DataServer` is an actor that responds to market data requests. There is always one data server that is local to a TradingEngine (one will be created by default if not supplied) and there may also be any number of remote `DataServer` actors in the cluster that provide additional data sets.

[Read the source](https://github.com/infixtrading/flashbot/blob/master/modules/server/src/main/scala/com/infixtrading/flashbot/engine/DataServer.scala) for to get an idea of how it runs.

### `Strategy`
A `Strategy` is a class that contains the logic of a trading algorithm. By definition, it does not know if it's running in backtest, paper trading, or live trading mode.

[Read the source](https://github.com/infixtrading/flashbot/blob/master/modules/tests/jvm/src/main/scala/strategies/LookAheadCandleStrategy.scala) of an example strategy that is used in our unit tests. This is a strategy that sources itself with a randomly generated data stream of OHLC data and "cheats" by looking ahead by one time step to make a profitable trade. This is used in testing to ensure that a strategy with perfect information of the future never makes an unprofitable trade.

