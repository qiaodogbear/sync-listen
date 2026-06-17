FROM node:22-alpine

WORKDIR /app

# 先复制 package.json 并安装全部依赖（含 TypeScript 等构建工具）
COPY backend/package.json backend/package-lock.json ./
RUN npm ci

# 复制源码并构建
COPY backend/ ./
RUN npm run build

# 清理 dev 依赖，减小镜像体积
RUN npm prune --omit=dev

RUN mkdir -p /data/audio /data/tmp

VOLUME ["/data"]

ENV HOST=0.0.0.0
ENV PORT=3000
ENV DATABASE_PATH=/data/sync-listen.sqlite
ENV AUDIO_STORAGE_PATH=/data/audio
ENV TEMP_UPLOAD_PATH=/data/tmp

EXPOSE 3000

CMD sh -c "mkdir -p /data/audio /data/tmp && node dist/db/migrate.js && node dist/server.js"
