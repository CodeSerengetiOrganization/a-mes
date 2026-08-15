# Build the jar first: ./mvnw verify (this directory)
FROM eclipse-temurin:21-jre
WORKDIR /app
# Version comes from pom — do not hardcode SNAPSHOT in the path
COPY target/mes-api-*.jar app.jar
EXPOSE 8080
# Heap from container cgroup (k3s limit 512Mi) — leave headroom for non-heap / OOMKill
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
