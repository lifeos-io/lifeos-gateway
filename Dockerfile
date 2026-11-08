# The jar is built outside the image (locally or in CI) with `mvn package`.
# Building inside the image would need its own access to GitHub Packages for the parent POM.
FROM eclipse-temurin:25-jre

WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --no-create-home app

COPY target/*.jar app.jar

USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
