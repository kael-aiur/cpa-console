# Build the frontend and Spring Boot application in one reproducible stage.
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app
COPY pom.xml ./
COPY console-core/pom.xml console-core/pom.xml
COPY console-server/pom.xml console-server/pom.xml

# Warm the Maven dependency cache before copying source files.
RUN mvn -B dependency:go-offline -pl console-server -am -DskipTests

COPY console-core console-core
COPY console-server console-server
COPY console-page console-page

RUN mvn -B package -DskipTests

# Keep the runtime image small and free of Maven/Node tooling.
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=builder /app/console-server/target/console-server-*.jar app.jar

ENV JAVA_OPTS=""
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
