import React from 'react';
import ReactDOM from 'react-dom/client';
import {RouterProvider} from 'react-router-dom';
import {App as AntdApp, ConfigProvider} from 'antd';
import zhCN from 'antd/locale/zh_CN';
import {router} from './router';
import MessageBridge from './components/MessageBridge';
import './styles/tokens.css';

// antd 全局主题：主色/圆角与 ds token 对齐（tailwind.config.js 的 ds-accent）。
// 组件级细节（表格行高、Modal 样式等）仍由 tokens.css 的 prototype-* 覆盖类承担，
// 能用 token 表达的部分优先收敛到这里。

// 与 tokens.css 的 --color-accent（79 70 229）等同值；antd token 只接受具体颜色值，无法用 Tailwind class
const DS_ACCENT_HEX = '#4f46e5';

// 与 tokens.css 的 --color-text-* / --color-border-* 等同值（UI 改进 Phase 6-A 深度对齐）
const DS_TEXT = '#0f172a';
const DS_TEXT_SECONDARY = '#475569';
const DS_TEXT_TERTIARY = '#64748b'; // 与提亮后 muted 同值
const DS_BORDER = '#cdd3dc';
const DS_BORDER_SECONDARY = '#e2e6ed';

// antd 原生组件（Select 下拉 / Switch / Tabs / Tooltip / Modal.confirm 等）统一字体栈（Phase 2，中文感知）
const DS_FONT_FAMILY = `-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'Helvetica Neue', Arial, sans-serif`;

ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
        <ConfigProvider
            locale={zhCN}
            theme={{
                token: {
                    colorPrimary: DS_ACCENT_HEX,
                    colorLink: DS_ACCENT_HEX,
                    colorInfo: DS_ACCENT_HEX,
                    borderRadius: 8,
                    fontSize: 13,
                    controlHeight: 32,
                    fontFamily: DS_FONT_FAMILY,
                    colorText: DS_TEXT,
                    colorTextSecondary: DS_TEXT_SECONDARY,
                    colorTextTertiary: DS_TEXT_TERTIARY,
                    colorBorder: DS_BORDER,
                    colorBorderSecondary: DS_BORDER_SECONDARY,
                },
            }}
        >
            <AntdApp>
                <MessageBridge/>
                <RouterProvider router={router}/>
            </AntdApp>
        </ConfigProvider>
    </React.StrictMode>,
);
