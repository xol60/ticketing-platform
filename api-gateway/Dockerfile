FROM maven:3.9.6-eclipse-temurin-21 AS builder
WORKDIR /build

# Root pom + ALL module poms so Maven reactor resolves correctly
COPY pom.xml .
COPY common-lib/pom.xml common-lib/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY auth-service/pom.xml auth-service/pom.xml
COPY ticket-service/pom.xml ticket-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY saga-orchestrator/pom.xml saga-orchestrator/pom.xml
COPY pricing-service/pom.xml pricing-service/pom.xml
COPY reservation-service/pom.xml reservation-service/pom.xml
COPY payment-service/pom.xml payment-service/pom.xml
COPY secondary-market-service/pom.xml secondary-market-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml

# Download dependencies (layer-cached separately from source)
RUN mvn dependency:go-offline -pl common-lib,api-gateway -am -q 2>/dev/null || true

# Copy only the source we need to compile
COPY common-lib/src common-lib/src
COPY api-gateway/src api-gateway/src

RUN mvn package -pl common-lib,api-gateway -am -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser
COPY --from=builder /build/api-gateway/target/*.jar app.jar
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+UseStringDeduplication -Djava.security.egd=file:/dev/./urandom"
EXPOSE 8090
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
