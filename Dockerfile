FROM openjdk:17-jdk-slim

WORKDIR /app

COPY target/ demo-ai-1-0.0.1-SNAPSHOT.jar

EXPOSE 8080

CMD ["java", "-jar", "app.jar"]