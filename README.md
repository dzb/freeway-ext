# freeway-ext

Optional third-party integrations for the [Freeway](https://github.com/dzb/freeway) framework.
The core is full-stack but zero-dependency; these modules add capabilities that require external libraries.

| Module | Description | External Dependency |
|--------|-------------|-------------------|
| `freeway-http-robaho` | High-performance HTTP engine with WebSocket | [robaho/httpserver](https://github.com/robaho/httpserver) |
| `freeway-http-undertow` | Undertow HTTP engine adapter | [Undertow](https://undertow.io) 2.3 |
| `freeway-http-jetty` | Jetty 12 HTTP engine + WebSocket adapter | [Jetty](https://jetty.org) 12.1 |
| `freeway-db-hikari` | HikariCP connection pool adapter | [HikariCP](https://github.com/brettwooldridge/HikariCP) 6.2 |
| `freeway-mq-kafka` | Kafka EventBus bridge for distributed pub/sub | [Kafka Clients](https://kafka.apache.org) 3.9 |

## Install

Add the Maven Central snapshot repository, then pick the modules you need:

```xml
<dependency>
    <groupId>com.jujin8.freeway</groupId>
    <artifactId>freeway-http-underto</artifactId>
    <version>${freeway.version}</version>
</dependency>
```

Versions track the Freeway core framework for guaranteed compatibility.

## Build

Requires JDK 25+. Build Freeway core first, then extensions:

```bash
# 1. Install core modules into local Maven repository
cd freeway && mvn install -DskipTests

# 2. Build extensions
cd freeway-ext && mvn test
```
