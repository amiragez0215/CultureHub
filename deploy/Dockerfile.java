FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml ./
COPY culturehub-contracts culturehub-contracts
COPY culturehub-business culturehub-business
COPY culturehub-api culturehub-api
COPY culturehub-notification-worker culturehub-notification-worker
COPY culturehub-gateway culturehub-gateway
COPY culturehub-service-runtime culturehub-service-runtime
COPY culturehub-knowledge culturehub-knowledge
COPY culturehub-ai culturehub-ai
RUN mvn -B -DskipTests package
FROM eclipse-temurin:17-jre
ARG MODULE=culturehub-api
WORKDIR /app
COPY --from=build /src/${MODULE}/target/${MODULE}-1.0.0.jar app.jar
ENV TZ=Asia/Shanghai
ENTRYPOINT ["java","-Duser.timezone=Asia/Shanghai","-jar","app.jar"]
