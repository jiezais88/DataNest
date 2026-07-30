/// <reference types="node" />
import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'
import {visualizer} from 'rollup-plugin-visualizer'

const isAnalyze = process.env.ANALYZE === 'true'

export default defineConfig({
    plugins: [
        react(),
        ...(isAnalyze
            ? [
                visualizer({
                    open: false,
                    filename: '../stats/stats.html',
                    gzipSize: true,
                    brotliSize: true,
                }),
            ]
            : []),
    ],
    server: {
        host: '0.0.0.0',
        port: 3000,
        proxy: {
            '/api': {
                target: 'http://localhost:8080',
                changeOrigin: true,
            },
        },
    },
    build: {
        minify: 'terser',
        terserOptions: {
            compress: {
                drop_console: true,
                drop_debugger: true,
            },
        },
        cssCodeSplit: true,
        chunkSizeWarningLimit: 500,
        rollupOptions: {
            output: {
                manualChunks: {
                    'vendor-react': ['react', 'react-dom', 'react-router-dom'],
                    'vendor-antd': ['antd', '@ant-design/icons'],
                    'vendor-icons': ['lucide-react', 'react-icons'],
                    'vendor-utils': ['axios', 'zustand', 'cron-parser', 'cronstrue', 'tailwind-merge'],
                },
            },
        },
    },
})
