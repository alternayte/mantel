# One image, two run modes. Built from a clean clone with nothing installed but Docker, because
# self-hosting is a first-class path and "build it yourself first" is not one (SDD.md 11).

FROM oven/bun:1.2-alpine AS web
WORKDIR /web
COPY web/package.json web/bun.lock ./
RUN bun install --frozen-lockfile
COPY web/ ./
RUN bun run build

FROM gradle:8.14-jdk21 AS server
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
# Resolve dependencies before the sources land, so a code change does not re-download the world.
RUN gradle --no-daemon dependencies --quiet || true
COPY src/ src/
COPY --from=web /build/web-resources/ build/web-resources/
RUN gradle --no-daemon buildFatJar -x test --quiet

FROM eclipse-temurin:21-jre-noble
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ffmpeg \
      libvips-tools \
      # libvips can write AVIF only when libheif has an AV1 encoder. Without this the photo
      # pipeline fails on its third derivative, in production, one job at a time.
      libheif-plugin-aomenc \
      libheif-plugin-libde265 \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=server /src/build/libs/*-all.jar /app/mantel.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/mantel.jar"]
