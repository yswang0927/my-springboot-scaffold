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
    assetsDir: "assets",
    sourcemap: false,
    minify: 'esbuild',
    chunkSizeWarningLimit: 1000,
    rollupOptions: {
      output: {
        format: "es",
        inlineDynamicImports: false,
        entryFileNames: "assets/js/[name]-[hash].js",
        chunkFileNames: "assets/js/[name]-[hash].js",
        experimentalMinChunkSize: 500 * 1024,
        // 拆分包
        manualChunks(id) {
          // 将 node_modules 中的不同库拆分成单独的包
          if (id.includes('node_modules')) {
            if (id.includes('/react/') || id.includes('/react-dom/') || id.includes('/react-')) {
              return 'vendor-react';
            }
            if (id.includes('/vue/') || id.includes('@vue') || id.includes('/vue-')) {
              return 'vendor-vue';
            }
            if (id.includes('/axios/') || id.includes('/lodash/') || id.includes('/ahooks/')) {
              return 'vendor-common';
            }
            if (id.includes('/filepond/')) {
              return 'vendor-filepond';
            }
            if (id.includes('/zod/')) {
              return 'vendor-zod';
            }
            if (id.includes('/msgpackr/')) {
              return 'vendor-msgpackr';
            }
            if (id.includes('echarts') || id.includes('echarts-wordcloud')) {
              return 'vendor-echarts';  
            }
            if (id.includes('html2canvas')) {
              return 'vendor-html2canvas';
            }
            return 'vendor';
          }
        },
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
