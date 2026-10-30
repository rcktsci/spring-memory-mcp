FROM rcktsci/java-tooling:25

WORKDIR /application

COPY target/*.jar /application/spring-memory-mcp.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "spring-memory-mcp.jar"]
