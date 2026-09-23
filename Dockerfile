# Builds and runs Roundtable's headless CLI mode — no window, API-mode agents only (no CLI
# tools installed in the image). Not for the windowed app; this is for a self-hosted server,
# a cron job, or a CI step that runs one debate and exits.
#
# Build:  docker build -t roundtable .
# Run:    docker run --rm \
#           -e ANTHROPIC_API_KEY=sk-ant-... \
#           -v roundtable-state:/root/.aidebate \
#           roundtable --topic "..." --agent anthropic --agent gemini --project "Server runs"

# No Gradle wrapper is committed to this repo yet (see README) — the gradle:jdk21 image
# provides a system Gradle instead so this build doesn't depend on that being fixed first.
FROM gradle:8-jdk21 AS build
WORKDIR /src
COPY . .
RUN gradle :desktopApp:packageUberJarForCurrentOS --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/desktopApp/build/compose/jars/*.jar roundtable.jar
ENTRYPOINT ["java", "-jar", "roundtable.jar"]
CMD ["--help"]
