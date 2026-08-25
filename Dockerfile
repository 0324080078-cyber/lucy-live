# ---- Stage 1: build the PWA ----
FROM node:20-slim AS pwa
WORKDIR /pwa
COPY app/package*.json ./
RUN npm install
COPY app/ ./
# VITE_BACKEND left unset on purpose: the PWA then uses same-origin for the API.
RUN npm run build

# ---- Stage 2: backend serving API + relay + the built PWA ----
FROM node:20-slim
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY backend/package*.json ./
RUN npm install --omit=dev
COPY backend/ ./
COPY --from=pwa /pwa/dist /app/pwa

ENV PWA_DIR=/app/pwa
ENV PORT=8080
EXPOSE 8080
CMD ["node", "server.js"]
