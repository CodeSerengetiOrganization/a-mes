# Canonical image build: services/mes-api/Dockerfile (CI context).
# This copy is for local k3s notes only — do not -f this path from a
# services/mes-api context (BuildKit rejects Dockerfile outside context).
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/mes-api-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
