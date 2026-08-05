// 必须在 antd / router 等任何用到 dayjs 的模块之前导入并设置中文 locale。
// dayjs locale 文件是 UMD，在 vite 浏览器构建下走 globalThis.dayjs 分支但
// vite 不会把 dayjs 挂到全局，仅靠 `import 'dayjs/locale/zh-cn'` 副作用 import
// 会导致月份面板永远是 Jan/Feb/Mar。这里 explicit import locale 对象后直接
// 传给 dayjs.locale() 注册并设为默认，同时把 dayjs 实例挂到 window 兜底
// 防止任何模块走 UMD 全局分支。
import dayjs from 'dayjs';
import zhCNLocale from 'dayjs/locale/zh-cn';
(window as unknown as {dayjs: typeof dayjs}).dayjs = dayjs;
dayjs.locale(zhCNLocale);

import React from 'react';
import ReactDOM from 'react-dom/client';
import {RouterProvider} from 'react-router-dom';
import {App as AntdApp, ConfigProvider} from 'antd';
import zhCN from 'antd/locale/zh_CN';
import {router} from './router';
import MessageBridge from './components/MessageBridge';
import './styles/tokens.css';

// 给 antd DatePicker 显式补中文月份短名（"1月"~"12月"）。
// 关键背景：vite 把 antd 依赖的 dayjs 单独打进 vendor-antd chunk，与主入口的
// dayjs 是两个独立模块实例，各自维护 C（locales map）。在主入口调用
// dayjs.locale('zh-cn') 只会改主入口实例，无法影响 antd vendor 内的 dayjs 实例，
// 导致 antd DatePicker 的月份面板始终是英文。这里直接把 shortMonths 塞进 antd
// 的 zh_CN locale 对象，antd DatePicker 优先用 locale.shortMonths，不会再走
// dayjs.localeData().monthsShort() 的兜底。
const ZH_CN_SHORT_MONTHS = ['1月', '2月', '3月', '4月', '5月', '6月', '7月', '8月', '9月', '10月', '11月', '12月'];
const ZH_CN_SHORT_WEEK_DAYS = ['日', '一', '二', '三', '四', '五', '六'];
// 用类型断言绕过 spread 引入的 locale?: string 字段：antd Locale 类型要求
// lang.locale 是必填 string，spread 会把可选字段保留为 undefined。运行时
// locale 字段一定有值（antd/locale/zh_CN 内部已设置），只是 TS 类型较严。
const zhCNWithMonths = {
    ...zhCN,
    DatePicker: {
        ...zhCN.DatePicker,
        lang: {
            ...zhCN.DatePicker?.lang,
            shortMonths: ZH_CN_SHORT_MONTHS,
            shortWeekDays: ZH_CN_SHORT_WEEK_DAYS,
        },
    },
} as typeof zhCN;

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
            locale={zhCNWithMonths}
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
