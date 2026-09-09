FROM eclipse-temurin:17-jdk-alpine AS build

WORKDIR /workspace

COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
COPY payment-service-java/build.gradle payment-service-java/
COPY reconciliation-batch/build.gradle reconciliation-batch/
COPY mockpg/build.gradle mockpg/
COPY mockpg/src mockpg/src

RUN chmod +x gradlew && ./gradlew :mockpg:bootJar -x test --no-daemon

FROM eclipse-temurin:17-jre-alpine

RUN adduser -D -s /bin/false appuser

WORKDIR /app

COPY --from=build /workspace/mockpg/build/libs/*.jar app.jar

USER appuser

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
