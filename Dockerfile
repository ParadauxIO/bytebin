# syntax=docker/dockerfile:1
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
# Java4Shell (CVE-2026-114938): eclipse-temurin:25-alpine still ships the affected 25.0.5+8.
# Swap in Adoptium's patched 25.0.5.1+2 JRE (checksum-pinned, amd64 — CI only
# builds linux/amd64) until the official image catches up, then drop this stage.
FROM eclipse-temurin:25-alpine AS patched-jre
ADD --checksum=sha256:b3d29ef8453da5e29c86b1ae08d8ed2a02597782b4ab5637e87f87b6bcc4cba9 \
    https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5.1%2B2/OpenJDK25U-jre_x64_alpine-linux_hotspot_25.0.5.1_2.tar.gz /tmp/jre.tar.gz
RUN mkdir /jre && tar -xzf /tmp/jre.tar.gz -C /jre --strip-components=1 \
    && /jre/bin/java -version 2>&1 | grep -q '25.0.5.1+2'

FROM eclipse-temurin:25-alpine

# Patched JRE from the stage above (Java4Shell).
RUN rm -rf /opt/java/openjdk
COPY --from=patched-jre /jre /opt/java/openjdk

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
