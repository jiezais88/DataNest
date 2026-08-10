/// <reference types="node" />
import {fileURLToPath, URL} from 'node:url'
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
        resolve: {
            // 路径别名 @ → src（跨目录导入统一用 @/，替代多层 ../../；详见 conventions-frontend §2）
            alias: {'@': fileURLToPath(new URL('./src', import.meta.url))},
        },
        plugins: [
            react(),
            // 预压缩 .br（nginx brotli_static 直接吐，压缩率比 gzip 高 ~15-20%；nginx 需启用 ngx_brotli）。
            // 现代浏览器（2015+）均支持 brotli，gzip 冗余，删掉以减小 dist 磁盘体积；nginx brotli_static
            // 不支持时会回退到 gzip_static（gzip on 仍开启），网络传输无损失。
            viteCompression({
                verbose: false,
                threshold: 10240,
                algorithm: 'brotliCompress',
                ext: '.br',
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
                            // NOTE: monaco-editor / @monaco-editor/react 不纳入同步 manualChunks。
                            // 手动分组会强制 monaco 成为一个同步命名 chunk，并因副作用被主入口静态 import，
                            // 导致首屏 modulepreload 下载 ~2.6MB（即使不用编辑器）。交由 Rollup 归入引用的懒加载 chunk。
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
