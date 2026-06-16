FROM node:22-alpine

WORKDIR /app

COPY backend/package.json backend/package-lock.json ./
RUN npm ci --omit=dev

COPY backend/ ./

RUN npm run build

RUN mkdir -p /data/audio /data/tmp /app/data

VOLUME ["/app/data"]

ENV HOST=0.0.0.0
ENV PORT=3000
ENV DATABASE_PATH=/app/data/sync-listen.sqlite
ENV AUDIO_STORAGE_PATH=/app/data/audio
ENV TEMP_UPLOAD_PATH=/app/data/tmp

EXPOSE 3000

CMD ["node", "dist/server.js"]
