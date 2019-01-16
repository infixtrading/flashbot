# Flashbot
[![Build Status](https://travis-ci.org/infixtrading/flashbot.svg?branch=master)](https://travis-ci.org/infixtrading/flashbot)

Flashbot is a Java and Scala library for building, simulating, and running low-latency Cryptocurrency trading strategies as a cluster of trading engines and market data servers.

This project aims to provide a simple interface for building complex strategies as well as a common infrastructure to run them on.

## Overview
Market data servers (the `DataServer` actor) connect to outside data sources such as exchange websockets or 3rd party signals. They persist and serve that data. Here's a list of features:

* Pluggable data sources (supports market data for other financial markets too)
* Publish data streams (live, historical, or historical+live) to subscribers
* Works with any JDBC compatible database
* Delta compression for efficient streaming and storage of order books
* Data retention policies
* Tolerant to network and websocket failures (i.e. it manages the retries)

The other main actor is the `TradingEngine`. It handles things such as:

* Pluggable strategies
* Running backtests on data in the cluster
* Managing bots that run in live or paper mode
* Fault tolerance for strategy exceptions (bots are restored if they crash)
* Backtests simulate fees and network latency
* Currency conversions

## Docs

* Read the [Getting Started](https://github.com/infixtrading/flashbot/wiki/Getting-Started) page
* Follow along to some tutorials and examples:
  1. Built-in strategy: MACD Crossover backtest & bot (with [Scala](http://google.com) or [Java](http://google.com))
  2. Custom strategy: Building a simple market maker (with [Scala](http://google.com))
  3. Building a currency conversion service (with [Scala](http://google.com))
* [Flashbot Terminology](https://github.com/infixtrading/flashbot/wiki/Terminology)

## Status
This project is alpha software. In it's current state it's a good tool for prototyping and simulating strategies however the unit tests are minimal and it hasn't been battle tested in production yet. Do not use it out of the box for real trading yet! You have been warned.

The client library and actor APIs support both Java and Scala, however extensions such as custom exchanges, data sources, and strategies currently require Scala. Full Java support is planned for future releases.

## Configuration
Check out the default [configuration file](https://github.com/infixtrading/flashbot/blob/master/modules/server/src/main/resources/reference.conf) to see a list of available options. You can override any of the settings in your own `application.conf` file.

## Components
Flashbot fully embraces Akka for persistence, streaming, clustering, fault-tolerance, and concurrency control. [Akka Cluster 2.5.x](https://doc.akka.io/docs/akka/2.5/index-cluster.html) is a dependency, but running a cluster is not required. You may use Flashbot in standalone mode on a non-cluster actor system.

