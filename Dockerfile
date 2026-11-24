# syntax=docker/dockerfile:1

# Build: same Maven line as the wrapper, dependencies cached between builds.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package

# Run: JRE only, unprivileged user.
FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="brazil-macro-mcp" \
      org.opencontainers.image.description="MCP server for cited, fail-closed Banco Central do Brasil data" \
      org.opencontainers.image.source="https://github.com/gharachide/brazil-macro-mcp" \
      org.opencontainers.image.licenses="MIT"
RUN groupadd --gid 10001 mcp \
    && useradd --uid 10001 --gid mcp --create-home --shell /usr/sbin/nologin mcp
WORKDIR /app
COPY --from=build /workspace/target/brazil-macro-mcp.jar /app/brazil-macro-mcp.jar
USER 10001
# Listen on all interfaces inside the container; publish the port explicitly with -p.
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8080
# The audit log lives in /home/mcp/.brazil-macro-mcp; mount a volume there to keep it.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/brazil-macro-mcp.jar"]
