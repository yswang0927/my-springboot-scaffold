import { defineConfig } from 'vite'
import path from "path"
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src")
    }
  },
  build: {
    outDir: "dist",
    rollupOptions: {
      output: {
        entryFileNames: "assets/js/[name]-[hash].js",
        chunkFileNames: "assets/js/[name]-[hash].js",
        assetFileNames(assetInfo) {
          const name = assetInfo.name;
          if (name.endsWith(".css")) {
            return "assets/css/[name]-[hash].css";
          }
          const imgExts = [".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp"];
          if (imgExts.some((ext) => name.endsWith(ext))) {
            return "assets/img/[name]-[hash].[extname]";
          }
          return "assets/[name]-[hash].[extname]";
        }
      }
    }
  }
})
