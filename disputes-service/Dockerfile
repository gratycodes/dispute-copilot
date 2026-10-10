# Build context: repository root (needs data/seed.json).
FROM maven:3.9-eclipse-temurin-21 AS build
ARG IDEMPOTENCY_REV=050b9a596da6b59dac0de0f78f3a29ec7abc017d
WORKDIR /build
# idempotency-spring-boot-starter is not on Maven Central; build the pinned commit.
RUN git clone https://github.com/gratycodes/idempotency-spring-boot-starter.git starter \
 && cd starter && git checkout "$IDEMPOTENCY_REV" \
 && mvn -B -ntp -q install -DskipTests -pl idempotency-spring-boot-starter -am
COPY disputes-service/pom.xml disputes-service/pom.xml
RUN mvn -B -ntp -q -f disputes-service/pom.xml dependency:go-offline
COPY disputes-service/src disputes-service/src
RUN mvn -B -ntp -q -f disputes-service/pom.xml package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/disputes-service/target/disputes-service-*.jar app.jar
COPY data/seed.json seed.json
ENV SEED_PATH=/app/seed.json
EXPOSE 8080
USER 1000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
