FROM node:22-alpine

WORKDIR /app

COPY backend/package.json backend/package-lock.json ./
RUN npm ci --omit=dev

COPY backend/ ./

RUN npm run build

RUN mkdir -p /data/audio /data/tmp

VOLUME ["/data"]

ENV HOST=0.0.0.0
ENV PORT=3000
ENV DATABASE_PATH=/data/sync-listen.sqlite
ENV AUDIO_STORAGE_PATH=/data/audio
ENV TEMP_UPLOAD_PATH=/data/tmp

EXPOSE 3000

# 启动时自动创建数据目录并初始化数据库
CMD sh -c "mkdir -p /data/audio /data/tmp && node dist/db/migrate.js && node dist/server.js"
