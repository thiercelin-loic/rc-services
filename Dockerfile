# ==============================================================================
# Stage 1: Maven build (JDK 21) — ANTLR grammar generation + Spring Boot fat jar
# ==============================================================================
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /workspace/rc-services

ENV MAVEN_OPTS="-Xmx768m -XX:+UseSerialGC"

# Cache dependencies separately from sources
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline || true

COPY src ./src
RUN mvn -B -DskipTests clean package && \
    cp target/rc-services-1.0.0.jar /workspace/app.jar

# ==============================================================================
# Stage 2: JRE runtime (glibc-based image: DuckDB JNI does not run on Alpine/musl)
# ==============================================================================
FROM eclipse-temurin:21-jre AS runtime

RUN mkdir -p /data/parquet /staging /opt/duckdb/extensions /var/lib/rollercoaster/workspaces && \
    groupadd --system rc && useradd --system --gid rc rc && \
    chown -R rc:rc /data /staging /opt/duckdb /var/lib/rollercoaster

USER rc
WORKDIR /app
COPY --from=builder --chown=rc:rc /workspace/app.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
