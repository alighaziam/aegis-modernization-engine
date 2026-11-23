# Build with the repository's enforced Java 25 / Maven 3.9.11 toolchain.
FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

# Minimal, non-root runtime image; the CLI does not expose a network listener.
FROM eclipse-temurin:25-jre
RUN groupadd --system --gid 10001 aegis \
    && useradd --system --uid 10001 --gid aegis --home-dir /home/aegis --create-home aegis \
    && mkdir -p /opt/aegis /workspace /opt/aegis/examples \
    && chown -R 10001:10001 /workspace /home/aegis
WORKDIR /workspace
COPY --from=build --chown=10001:10001 /src/target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar /opt/aegis/aegis.jar
COPY --chown=10001:10001 examples/ /opt/aegis/examples/
USER 10001:10001
ENV HOME=/tmp \
    JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=/tmp -XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["java", "-jar", "/opt/aegis/aegis.jar"]
