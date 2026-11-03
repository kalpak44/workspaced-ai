# Packages the jar `./mvnw verify` already built, so CI compiles and tests once.
# Build it first: `./mvnw -B package -DskipTests && docker build .`

# Azul rebuilds this tag after Alpine security updates, which the Temurin JRE image lags.
FROM azul/zulu-openjdk-alpine:25-jre-headless AS layers
WORKDIR /layers
COPY target/workspaced-ai-api-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM azul/zulu-openjdk-alpine:25-jre-headless
LABEL org.opencontainers.image.source="https://github.com/kalpak44/workspaced-ai"
WORKDIR /app
# Least-changing layer first, so a code-only change pushes only the last one.
COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./
# A Deployment with runAsNonRoot refuses an image that declares no numeric USER.
USER 1000:1000
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
