# Builds and runs the server. Used by docker-compose.yml.
FROM gradle:9.8.1-jdk21 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN gradle installDist --no-daemon -q

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
COPY --from=build /src/build/install/parkinson-server /opt/parkinson-server
USER app
EXPOSE 8080
ENTRYPOINT ["/opt/parkinson-server/bin/parkinson-server"]
