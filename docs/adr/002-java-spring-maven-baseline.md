# ADR-002: Java, Spring Boot and Maven baseline

- Status: Accepted for Phase 1 bootstrap; review after the GIS spike
- Date: 2026-08-02
- Decision owners: OpenGIS maintainers

## Decision

Freeze the Phase 1 bootstrap on:

| Component | Version/baseline |
|---|---|
| JDK distribution | Eclipse Temurin 21 LTS |
| Java language/release target | 21 |
| Spring Boot | 4.1.0 |
| Spring Framework | Version managed by Spring Boot 4.1.0 |
| Apache Maven Wrapper | 3.9.10 |
| Source encoding | UTF-8 |

Java 25 is the current LTS in 2026, but OpenGIS intentionally selects Java 21 for Phase 1. GeoTools documents Java 17 as its target and tests pull requests on Java 17 and 21, while warning that building with newer JDK APIs is risky. Spring Boot 4.1.0 supports Java 17 through Java 26, so Java 21 sits inside both supported/tested ranges.

The bundled runtime will be created from an OpenJDK/Temurin build with `jlink`; the project must not require a system Java installation. Oracle JDK is not the redistribution default.

## Dependency policy

- Spring and third-party versions are managed centrally in `java-backend/pom.xml` and its BOM.
- Child modules do not declare independent versions unless an ADR records the exception.
- Maven Wrapper is the only documented Maven entrypoint in CI and developer instructions.
- No `SNAPSHOT`, milestone or release-candidate dependencies in production modules.
- GeoTools, JTS and optional GDAL versions will be frozen by a follow-up GIS spike ADR; Phase 1 must not guess their compatibility.

## Official compatibility evidence

- Spring Boot 4.1.0 system requirements: https://docs.spring.io/spring-boot/system-requirements.html
- Spring Boot project release: https://spring.io/projects/spring-boot/
- Apache Maven downloads: https://maven.apache.org/download.cgi
- GeoTools Java requirements: https://docs.geotools.org/latest/userguide/build/install/jdk.html

## Consequences

- The team gets records, sealed types, modern concurrency APIs and a stable LTS runtime.
- Java 25-only APIs cannot be used during the migration.
- A future JDK upgrade is deliberate and tested against GIS/native dependencies rather than automatic.
- Spring Boot 4 uses the current Jakarta/Spring generation, avoiding a planned Boot 3-to-4 migration during the rewrite.

## Review trigger

Review this ADR if any of the following occurs:

- the GeoTools/GDAL spike cannot pass on Temurin 21;
- Spring Boot 4.1 reaches end of open-source support before Java cutover;
- jlink or Electron packaging exposes a platform blocker;
- a required dependency needs a higher Java release.
