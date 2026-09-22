FROM eclipse-temurin:26-jdk-noble AS build

WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw --batch-mode --no-transfer-progress dependency:go-offline

COPY src src
RUN ./mvnw --batch-mode --no-transfer-progress clean package

FROM eclipse-temurin:26-jre-noble

RUN groupadd --system alco && useradd --system --gid alco --home-dir /app --shell /usr/sbin/nologin alco
WORKDIR /app
COPY --from=build /workspace/target/stv-alco-downloader-1.0.0-SNAPSHOT.jar /app/app.jar
RUN mkdir -p /data && chown -R alco:alco /app /data

USER alco
ENV ALCO_OUTPUT_DIR=/data
ENV ALCO_PERIOD=all
VOLUME ["/data"]
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
