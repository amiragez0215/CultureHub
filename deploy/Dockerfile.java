FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml ./
COPY culturehub-contracts culturehub-contracts
COPY culturehub-business culturehub-business
COPY culturehub-api culturehub-api
COPY culturehub-notification-worker culturehub-notification-worker
RUN mvn -B -DskipTests package
FROM eclipse-temurin:17-jre
ARG MODULE=culturehub-api
WORKDIR /app
COPY --from=build /src/${MODULE}/target/${MODULE}-1.0.0.jar app.jar
ENV TZ=Asia/Shanghai
ENTRYPOINT ["java","-Duser.timezone=Asia/Shanghai","-jar","app.jar"]
