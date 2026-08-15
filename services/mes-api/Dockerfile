# Build the jar first: ./mvnw verify (this directory)
FROM eclipse-temurin:21-jre
WORKDIR /app
# Version comes from pom — do not hardcode SNAPSHOT in the path
COPY target/mes-api-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
