# Runtime image for any service module. Build the jar first: mvn -pl <module> -am package
# docker build --build-arg MODULE=account-service -t banking/account-service .
FROM mirror.gcr.io/library/eclipse-temurin:17-jre-alpine

ARG MODULE
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY ${MODULE}/target/${MODULE}-*.jar app.jar

USER app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
  CMD wget -qO- http://localhost:${SERVER_PORT:-8080}/actuator/health/liveness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
