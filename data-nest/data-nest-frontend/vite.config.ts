/// <reference types="node" />
import {defineConfig, loadEnv} from 'vite'
import react from '@vitejs/plugin-react'
import {visualizer} from 'rollup-plugin-visualizer'

const isAnalyze = process.env.ANALYZE === 'true'

export default defineConfig(({mode}) => {
    // /api 代理目标可在 .env.development 或同名环境变量中覆盖（默认本机后端）
    const env = loadEnv(mode, process.cwd(), '')
    const apiTarget = env.VITE_API_TARGET || 'http://localhost:8080'

    return {
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
                    target: apiTarget,
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
                        'vendor-antd': ['antd'],
                        'vendor-icons': ['react-icons'],
                        'vendor-utils': ['axios', 'zustand', 'cron-parser', 'cronstrue', 'tailwind-merge'],
                    },
                },
            },
        },
    }
})
