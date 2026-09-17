# One image, two run modes. ffmpeg and libvips are here for --worker mode; the cost is paid in both
# modes deliberately, so a self-hoster starts with `docker compose up` (SDD.md 3.1).
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
COPY build/libs/*-all.jar /app/mantel.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/mantel.jar"]
