# Flashbot
[![Build Status](https://travis-ci.org/infixtrading/flashbot.svg?branch=master)](https://travis-ci.org/infixtrading/flashbot)
<a href='https://github.com/infixtrading/flashbot/blob/master/LICENSE'>
  <img src='https://img.shields.io/github/license/infixtrading/flashbot.svg?longCache=true' alt='License' />
</a>

Flashbot is a Java and Scala framework for building, simulating, and running robust, low-latency Cryptocurrency trading strategies as a cluster of trading engines and market data servers.

Crypto markets are unique in the world of finance because the "playing field is level". I.e. If you have an idea for a high-frequency trading strategy, you don't need to pay millions for infastructure, data, and co-location to get started. But you do still have to write performant software. Flashbot helps with that by providing a simple interface for building complex strategies and a common infrastructure to run them on.

## Here for a code review?
Check out the following core files (on the branch v1):

*Interfaces*
* [Strategy.scala](https://github.com/infixtrading/flashbot/blob/v1/modules/core/jvm/src/main/scala/flashbot/core/Strategy.scala)
* [DataSource.scala](https://github.com/infixtrading/flashbot/blob/v1/modules/core/jvm/src/main/scala/flashbot/core/DataSource.scala)
* [Exchange.scala](https://github.com/infixtrading/flashbot/blob/v1/modules/core/jvm/src/main/scala/flashbot/core/Exchange.scala)

*Core*
* [TradingEngine.scala](https://github.com/infixtrading/flashbot/blob/v1/modules/server/src/main/scala/flashbot/core/TradingEngine.scala)
* [DataSourceActor.scala](https://github.com/infixtrading/flashbot/blob/v1/modules/server/src/main/scala/flashbot/server/DataSourceActor.scala)
* [BackfillService.scala](https://github.com/infixtrading/flashbot/blob/v1/modules/server/src/main/scala/flashbot/server/BackfillService.scala)
* [TradingSessionActor.scala](https://github.com/infixtrading/flashbot/blob/v1/modules/server/src/main/scala/flashbot/server/TradingSessionActor.scala)
* [DataServer.scala](https://github.com/infixtrading/flashbot/blob/v1/modules/server/src/main/scala/flashbot/core/DataServer.scala)

*Sample strategy*
* [MarketMaker.scala](https://github.com/infixtrading/flashbot/blob/v1/modules/core/jvm/src/main/scala/flashbot/strategies/MarketMaker.scala)


## Features
* Cross-platform. Windows, Linux, and Mac OS
* Pluggable strategies, exchanges, and data sources
  * Market data server works for other financial markets too
* Backtests with simulated fees and latency
* Support for derivatives such as the XBTUSD futures contract
* Bots that run in live or paper mode
* Fault tolerance for strategies (bots are restored if they crash)
* Dashboards & alerting
* Configurable market data database via JDBC
* Delta compression for efficient streaming and storage of order books
* Automatic historical data collection and retention
* Tolerant to network and websocket failures (i.e. it manages the retries)

## Docs
Check out the [wiki](https://github.com/infixtrading/flashbot/wiki) for documentation. 

## Installation
Flashbot is published to Bintray, so you'll have to add our repository as a resolver in your build tool and then list Flashbot as a dependency.

### SBT
Add the following line to your project settings in build.sbt:
```
resolvers += Resolver.bintrayRepo("infixtrading", "flashbot")
```
And then add the `flashbot-client` and `flashbot-server` dependencies as well:
```
libraryDependencies ++= Seq(
  "com.infixtrading" %% "flashbot-client" % "0.0.1",
  "com.infixtrading" %% "flashbot-server" % "0.0.1"
)
```

### Gradle
First add the repo to your gradle.build file:
```
repositories {
    maven {
        url "https://dl.bintray.com/infixtrading/flashbot"
    }
}
```
Then add the dependencies to the file:
```
compile 'com.infixtrading:flashbot-client_2.12:0.0.1'
compile 'com.infixtrading:flashbot-server_2.12:0.0.1'
```

### Maven
First add the repository to your pom.xml file:
```xml
...
 <repositories>
   ...
   <repository>
       <id>bintray-<username>-maven</id>
       <name>flashboot</name>
       <url>https://dl.bintray.com/infixtrading/flashbot</url>
   </repository>
   ...
 </repositories>
...
```
Add the following dependencies to your pom.xml:
```xml
...
  <dependencies>
    ...
    <dependency>
      <groupId>com.infixtrading</groupId>
      <artifactId>flashbot-server_2.12</artifactId>
      <version>0.0.1</version>
      <type>pom</type>
    </dependency>

    <dependency>
      <groupId>com.infixtrading</groupId>
      <artifactId>flashbot-client_2.12</artifactId>
      <version>0.0.1</version>
      <type>pom</type>
    </dependency>
    ...
  </dependencies>
...
```

## Configuration
The default [configuration file](https://github.com/infixtrading/flashbot/blob/master/modules/server/src/main/resources/reference.conf) contains a list of available options. You can override any of the settings in your own `application.conf` file.

## Requirements
Akka is used for persistence, streaming, clustering, fault-tolerance, and concurrency control. [Akka Cluster 2.5.x](https://doc.akka.io/docs/akka/2.5/index-cluster.html) is a dependency, but running a cluster is not required. You may use Flashbot in standalone mode on a non-cluster actor system.

## Status
This project is alpha software. In it's current state it's a good tool for prototyping and simulating strategies however the unit tests are minimal and it hasn't been battle tested in production yet. Do not use it out of the box with real money yet! You have been warned.

## Contributing
Contributions are very welcome! This project is young, it's scope is large, and needs more manpower. Please propose issues through GitHub first before submitting a pull request.
