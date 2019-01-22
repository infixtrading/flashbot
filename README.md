# Flashbot
[![Build Status](https://travis-ci.org/infixtrading/flashbot.svg?branch=master)](https://travis-ci.org/infixtrading/flashbot)

Flashbot is a Java and Scala framework for building, simulating, and running robust, low-latency Cryptocurrency trading strategies as a cluster of trading engines and market data servers.

Crypto markets are unique in the world of finance because the "playing field is level". I.e. If you have an idea for a high-frequency trading strategy, you don't need to pay millions for infastructure, data, and co-location to get started. But you do still have to write the low-latency software. Flashbot helps with that by providing a simple interface for building complex strategies and a common infrastructure to run them on.

## Features
The main component of Flashbot is the `TradingEngine` actor. It handles things such as:
* Pluggable strategies
* Running backtests on data in the cluster
* Managing bots that run in live or paper mode
* Fault tolerance for strategy exceptions (bots are restored if they crash)
* Backtests simulate fees and network latency
* Currency conversions
* Dashboards, JSON API, CSV exports
* Alerting

Market data servers (the `DataServer` actor) connect to outside data sources such as exchange websockets or 3rd party signals. They persist and serve that data. Here's a list of features:
* Pluggable data sources (supports market data for other financial markets too)
* Publish data streams (live, historical, or historical+live) to subscribers
* Works with any JDBC compatible database
* Delta compression for efficient streaming and storage of order books
* Data retention policies
* Automatic backfilling of historical data
* Tolerant to network and websocket failures (i.e. it manages the retries)

## Docs
Check out the [Getting Started](https://github.com/infixtrading/flashbot/wiki/Getting-Started) page for documentation.

## Configuration
The default [configuration file](https://github.com/infixtrading/flashbot/blob/master/modules/server/src/main/resources/reference.conf) contains a list of available options. You can override any of the settings in your own `application.conf` file.

## Requirements
Flashbot fully embraces Akka for persistence, streaming, clustering, fault-tolerance, and concurrency control. [Akka Cluster 2.5.x](https://doc.akka.io/docs/akka/2.5/index-cluster.html) is a dependency, but running a cluster is not required. You may use Flashbot in standalone mode on a non-cluster actor system.

## Status
This project is alpha software. In it's current state it's a good tool for prototyping and simulating strategies however the unit tests are minimal and it hasn't been battle tested in production yet. Do not use it out of the box with real money yet! You have been warned.

The client library and actor APIs support both Java and Scala, however extensions such as custom exchanges, data sources, and strategies currently require Scala. Full Java support is planned for future releases.

## Contributing
Contributions are very welcome! This project is young, it's scope is large, and needs more manpower. Please propose issues through GitHub first before submitting a pull request.
