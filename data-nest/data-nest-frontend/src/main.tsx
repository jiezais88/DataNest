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
ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
        <ConfigProvider
            locale={zhCN}
            theme={{
                token: {
                    colorPrimary: '#4f46e5',
                    colorLink: '#4f46e5',
                    borderRadius: 8,
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
