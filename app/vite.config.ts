import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: "autoUpdate",
      manifest: {
        name: "Lucy Live",
        short_name: "LucyLive",
        description: "Realtime avatar + voice changer, live streaming",
        display: "standalone",
        orientation: "portrait",
        background_color: "#0b0b0f",
        theme_color: "#0b0b0f",
        icons: [
          { src: "icon-192.png", sizes: "192x192", type: "image/png" },
          { src: "icon-512.png", sizes: "512x512", type: "image/png" },
        ],
      },
    }),
  ],
  server: { host: true },
});
