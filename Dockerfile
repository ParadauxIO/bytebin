# bytebin Dockerfile
# Copyright (c) lucko - licenced MIT

# --------------
# BUILD PROJECT STAGE
# --------------
FROM maven:3-eclipse-temurin-25-alpine AS build-project

# compile the project
WORKDIR /bytebin
COPY pom.xml ./
COPY src/ ./src/
RUN mvn --no-transfer-progress -B package


# --------------
# RUN STAGE
# --------------
FROM eclipse-temurin:25-alpine

# update Alpine packages to patch CVE-2026-25646 (libpng)
RUN apk update && apk upgrade --no-cache

RUN addgroup -S bytebin && adduser -S -G bytebin bytebin
USER bytebin

# copy app from build stage
WORKDIR /opt/bytebin
COPY --from=build-project /bytebin/target/bytebin.jar .

# define a volume for the stored content
RUN mkdir content logs
VOLUME ["/opt/bytebin/content", "/opt/bytebin/logs"]

# define a healthcheck
HEALTHCHECK --interval=1m --timeout=5s \
    CMD wget http://localhost:8080/health -q -O - | grep -c '{"status":"ok"}' || exit 1

# run the app
CMD ["java", "-jar", "bytebin.jar"]
EXPOSE 8080/tcp
