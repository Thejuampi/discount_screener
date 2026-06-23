import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // Tauri needs a fixed port and host
  server: {
    port: 5173,
    strictPort: true,
    host: 'localhost',
  },
  // Ensure assets are referenced correctly in Tauri
  base: './',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
  envPrefix: ['VITE_', 'TAURI_'],
})
