FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src/main ./src/main
COPY public ./public
RUN mvn -B -q -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --gid 10001 futurecode && useradd --uid 10001 --gid futurecode --no-create-home --shell /usr/sbin/nologin futurecode
COPY --from=build --chown=10001:10001 /workspace/target/futurecode.jar ./futurecode.jar
ENV APP_ENV=production HOST=0.0.0.0 PORT=4317
ENV JAVA_TOOL_OPTIONS="-Xms32m -Xmx160m -Xss512k"
USER 10001:10001
EXPOSE 4317
ENTRYPOINT ["java", "-jar", "/app/futurecode.jar"]
