FROM eclipse-temurin:17-jdk-alpine AS build

WORKDIR /workspace

COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
# settings.gradle 이 포함하는 모든 모듈의 빌드 파일이 있어야 멀티 프로젝트 빌드가 성립한다.
COPY mockpg/build.gradle mockpg/
COPY reconciliation-batch/build.gradle reconciliation-batch/
COPY payment-service-java/build.gradle payment-service-java/
COPY payment-service-java/src payment-service-java/src

RUN chmod +x gradlew && ./gradlew :payment-service-java:bootJar -x test --no-daemon

FROM eclipse-temurin:17-jre-alpine

RUN adduser -D -s /bin/false appuser

WORKDIR /app

COPY --from=build /workspace/payment-service-java/build/libs/*.jar app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
