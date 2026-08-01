import React, {useEffect} from 'react';
import ReactDOM from 'react-dom/client';
import {RouterProvider} from 'react-router-dom';
import {App as AntdApp, ConfigProvider} from 'antd';
import zhCN from 'antd/locale/zh_CN';
import {router} from './router';
import {injectMessageApi} from './utils/notify';
import './styles/tokens.css';

// 把 antd <App> 上下文的 message 实例注入 utils/notify，消灭静态 message
// "Static function can not consume context like dynamic theme" 警告
function MessageBridge() {
    const {message} = AntdApp.useApp();
    useEffect(() => {
        injectMessageApi(message);
    }, [message]);
    return null;
}

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
