import {message as staticMessage} from 'antd';
import type {MessageInstance} from 'antd/es/message/interface';
import type {ReactNode} from 'react';

/**
 * 全局消息提示的唯一入口。历史背景：49 处直接调 antd 静态 message，
 * 在 ConfigProvider 动态主题下会触发 "Static function can not consume context
 * like dynamic theme" 警告。现在 components/MessageBridge.tsx 会把
 * antd <App> 的 message 实例注入这里，业务代码一律用 notify.xxx，
 * 不要再 import {message} from 'antd'。
 */
let messageApi: MessageInstance | null = null;

/** 由 components/MessageBridge.tsx 在挂载时调用 */
export function injectMessageApi(api: MessageInstance) {
    messageApi = api;
}

// bridge 挂载前（理论上只有极早期）退回静态 message，保证提示不丢
const api = () => messageApi ?? staticMessage;

export const notify = {
    success: (content: ReactNode, duration?: number) => api().success(content, duration),
    error: (content: ReactNode, duration?: number) => api().error(content, duration),
    warning: (content: ReactNode, duration?: number) => api().warning(content, duration),
    info: (content: ReactNode, duration?: number) => api().info(content, duration),
};
