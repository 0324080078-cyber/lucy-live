FROM node:20-slim

# ffmpeg is required by the /stream RTMP relay
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY package*.json ./
RUN npm install --omit=dev

COPY . .

# Optional: copy a prebuilt PWA here (see HOSTING.md) and set PWA_DIR to serve it.
# ENV PWA_DIR=/app/pwa

ENV PORT=8080
EXPOSE 8080

CMD ["node", "server.js"]
