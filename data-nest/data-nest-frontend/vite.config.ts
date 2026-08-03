/// <reference types="node" />
import {defineConfig, loadEnv} from 'vite'
import react from '@vitejs/plugin-react'
import {visualizer} from 'rollup-plugin-visualizer'
import viteCompression from 'vite-plugin-compression'

const isAnalyze = process.env.ANALYZE === 'true'

export default defineConfig(({mode}) => {
    // /api 代理目标可在 .env.development 或同名环境变量中覆盖（默认本机后端）
    const env = loadEnv(mode, process.cwd(), '')
    const apiTarget = env.VITE_API_TARGET || 'http://localhost:8080'

    return {
        plugins: [
            react(),
            // 构建时预压缩 .gz（nginx gzip_static 直接吐预压缩文件，省去每次请求实时 gzip）
            viteCompression({
                verbose: false,
                threshold: 10240, // 仅压缩 > 10KB 的资产
                algorithm: 'gzip',
                ext: '.gz',
                deleteOriginFile: false,
            }),
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
            // esbuild 压缩：比 terser 快约 2 倍；drop 保留去除 console/debugger 的效果
            minify: 'esbuild',
            esbuild: {
                drop: ['console', 'debugger'],
            },
            cssCodeSplit: true,
            chunkSizeWarningLimit: 500,
            rollupOptions: {
                output: {
                    manualChunks(id) {
                        if (id.includes('node_modules')) {
                            if (['/react/', '/react-dom/', '/react-router-dom/'].some(p => id.includes(p))) {
                                return 'vendor-react';
                            }
                            if (id.includes('/antd/') || id.includes('/@ant-design/icons/')) {
                                return 'vendor-antd';
                            }
                            if (id.includes('/react-icons/')) {
                                return 'vendor-icons';
                            }
                            if (['/axios/', '/zustand/', '/cron-parser/', '/cronstrue/', '/tailwind-merge/'].some(p => id.includes(p))) {
                                return 'vendor-utils';
                            }
                            if (id.includes('/monaco-editor/') || id.includes('/@monaco-editor/react/')) {
                                return 'vendor-monaco';
                            }
                            if (id.includes('/reactflow/')) {
                                return 'vendor-reactflow';
                            }
                        }
                    },
                },
            },
        },
    }
})
