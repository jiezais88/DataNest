import {useEffect, useRef} from 'react';
import {App as AntdApp} from 'antd';

// 构建时由 vite 插件写入 dist/version.json（见 vite.config.ts versionJsonPlugin）。
// 请求带时间戳 + cache: no-store，绕过浏览器对 GET 的启发式缓存，保证每次拿到最新构建。
const VERSION_URL = '/version.json';
const CHECK_INTERVAL = 60 * 1000; // 每 60s 轮询一次

/**
 * 版本轮询组件（方案一：版本文件 + 定时检测 + 提示刷新）。
 *
 * 背景：dist/index.html 由 nginx 配置为 no-cache（刷新一次即可拿到最新 HTML 与
 * 带 hash 的新 JS），但已打开的页面不会主动重新请求，用户不刷新就看不到新版。
 * 本组件挂载时记录当前版本，之后每 60s 静默拉取 /version.json 对比，发现新版本
 * 弹一次 antd Modal 提示刷新，随后停止轮询（避免重复打扰）。
 *
 * dev 模式下没有 version.json（vite 不执行 generateBundle 钩子），fetch 返回
 * 404，loadVersion 返回 null，本组件自动跳过检测，不影响本地开发。
 */
export default function VersionChecker() {
    const {modal} = AntdApp.useApp();
    const currentRef = useRef<string | null>(null);
    const timerRef = useRef<number | null>(null);

    useEffect(() => {
        let disposed = false;

        const loadVersion = async (): Promise<string | null> => {
            try {
                const res = await fetch(`${VERSION_URL}?t=${Date.now()}`, {cache: 'no-store'});
                if (!res.ok) return null;
                const data = (await res.json()) as {version?: unknown};
                return typeof data?.version === 'string' ? data.version : null;
            } catch {
                return null;
            }
        };

        const check = async () => {
            const latest = await loadVersion();
            if (disposed || !latest || latest === currentRef.current) return;
            // 已发现新版本，停止后续轮询，避免重复弹窗
            if (timerRef.current !== null) {
                window.clearInterval(timerRef.current);
                timerRef.current = null;
            }
            currentRef.current = latest;
            modal.warning({
                title: '发现新版本',
                content: '系统已发布新版本，刷新页面后即可体验最新功能。',
                okText: '立即刷新',
                cancelText: '稍后再说',
                onOk: () => window.location.reload(),
            });
        };

        (async () => {
            currentRef.current = await loadVersion();
            if (disposed || !currentRef.current) return;
            timerRef.current = window.setInterval(check, CHECK_INTERVAL);
        })();

        return () => {
            disposed = true;
            if (timerRef.current !== null) {
                window.clearInterval(timerRef.current);
            }
        };
    }, [modal]);

    return null;
}
