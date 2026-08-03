import tailwindcssAnimate from 'tailwindcss-animate';

/**
 * 颜色唯一来源是 src/styles/tokens.css :root 的 RGB 通道变量，这里只做桥接。
 * withAlpha 形式让 /50、/20 等透明度修饰可用。新增颜色请先在
 * tokens.css 定义变量，再在这里加一行，不要写死 hex。
 */
const withAlpha = (variable) => `rgb(var(${variable}) / <alpha-value>)`;

/** @type {import('tailwindcss').Config} */
export default {
    content: ['./index.html', './src/**/*.{ts,tsx}'],
    theme: {
        extend: {
            colors: {
                'ds-bg-root': withAlpha('--color-bg-root'),
                'ds-bg-surface': withAlpha('--color-bg-surface'),
                'ds-bg-elevated': withAlpha('--color-bg-elevated'),
                'ds-bg-hover': withAlpha('--color-bg-hover'),
                'ds-border-subtle': withAlpha('--color-border-subtle'),
                'ds-border-strong': withAlpha('--color-border-strong'),
                'ds-text-primary': withAlpha('--color-text-primary'),
                'ds-text-secondary': withAlpha('--color-text-secondary'),
                'ds-text-muted': withAlpha('--color-text-muted'),
                'ds-accent': {
                    DEFAULT: withAlpha('--color-accent'),
                    hover: withAlpha('--color-accent-hover'),
                    light: withAlpha('--color-accent-light'),
                    glow: 'rgb(var(--color-accent) / 0.12)',
                },
                'ds-danger': {
                    DEFAULT: withAlpha('--color-danger'),
                    hover: withAlpha('--color-danger-hover'),
                    light: withAlpha('--color-danger-light'),
                },
                'ds-success': {DEFAULT: withAlpha('--color-success'), light: withAlpha('--color-success-light')},
                'ds-warning': {DEFAULT: withAlpha('--color-warning'), light: withAlpha('--color-warning-light')},

                // DAG node status (Sprint 3)
                'ds-node-waiting': withAlpha('--color-node-waiting'),
                'ds-node-running': withAlpha('--color-node-running'),
                'ds-node-success': withAlpha('--color-node-success'),
                'ds-node-failed': withAlpha('--color-node-failed'),
                'ds-node-skipped': withAlpha('--color-node-skipped'),

                // DAG 节点类型色（条件分支 / 子 DAG）
                'ds-type-condition': {
                    DEFAULT: withAlpha('--color-type-condition'),
                    border: withAlpha('--color-type-condition-border'),
                    light: withAlpha('--color-type-condition-light'),
                    soft: withAlpha('--color-type-condition-soft'),
                },
                'ds-type-subdag': {
                    DEFAULT: withAlpha('--color-type-subdag'),
                    border: withAlpha('--color-type-subdag-border'),
                    light: withAlpha('--color-type-subdag-light'),
                    soft: withAlpha('--color-type-subdag-soft'),
                },

                // 数据源类型色（品牌色）
                'ds-type-mysql': withAlpha('--color-type-mysql'),
                'ds-type-postgresql': withAlpha('--color-type-postgresql'),
                'ds-type-doris': withAlpha('--color-type-doris'),
                'ds-type-oracle': withAlpha('--color-type-oracle'),
                'ds-type-sqlserver': withAlpha('--color-type-sqlserver'),
            },
            fontFamily: {
                sans: [
                    '-apple-system',
                    'BlinkMacSystemFont',
                    "'Segoe UI'",
                    "'PingFang SC'",
                    "'Hiragino Sans GB'",
                    "'Microsoft YaHei'",
                    "'Helvetica Neue'",
                    'Arial',
                    'sans-serif',
                ],
                mono: ["'SFMono-Regular'", 'ui-monospace', 'Menlo', 'Consolas', "'Liberation Mono'", 'monospace'],
            },
            fontSize: {
                'ds-badge': ['11px', {lineHeight: '1.4', fontWeight: '600'}],
                'ds-display': ['1.5rem', {lineHeight: '1.35', fontWeight: '800', letterSpacing: '-0.5px'}],
                'ds-heading': ['1.25rem', {lineHeight: '1.35', fontWeight: '700', letterSpacing: '-0.3px'}],
                'ds-subhead': ['1.0625rem', {lineHeight: '1.4', fontWeight: '700'}],
                'ds-body': ['0.875rem', {lineHeight: '1.6', fontWeight: '400'}],
                'ds-body-strong': ['0.875rem', {lineHeight: '1.6', fontWeight: '600'}],
                'ds-small': ['0.8125rem', {lineHeight: '1.5', fontWeight: '500'}],
                'ds-caption': ['0.75rem', {lineHeight: '1.4', fontWeight: '600', letterSpacing: '0.6px'}],
                'ds-nano': ['0.6875rem', {lineHeight: '1.4', fontWeight: '600', letterSpacing: '1px'}],
            },
            spacing: {
                'ds-1': '4px', 'ds-2': '8px', 'ds-3': '12px',
                'ds-4': '16px', 'ds-5': '20px', 'ds-6': '24px',
                'ds-8': '32px', 'ds-10': '40px', 'ds-12': '48px',
            },
            borderRadius: {
                'ds-sm': '8px', 'ds-md': '12px', 'ds-lg': '16px', 'ds-full': '100px',
                'ds-xs': '6px',
            },
            boxShadow: {
                'ds-xs': '0 1px 2px rgba(0,0,0,0.04)',
                'ds-sm': '0 1px 3px rgba(0,0,0,0.06), 0 1px 2px rgba(0,0,0,0.04)',
                'ds-md': '0 4px 6px rgba(0,0,0,0.04), 0 2px 4px rgba(0,0,0,0.03)',
                'ds-lg': '0 10px 15px rgba(0,0,0,0.05), 0 4px 6px rgba(0,0,0,0.03)',
                'ds-xl': '0 20px 25px rgba(0,0,0,0.06), 0 10px 10px rgba(0,0,0,0.03)',
            },
            zIndex: {
                'ds-elevated': '100', 'ds-overlay': '200', 'ds-dialog': '300',
            },
            width: {
                'ds-node-palette': '180px',
                'ds-property-panel': '260px',
            },
            transitionTimingFunction: {
                'ds-fast': 'cubic-bezier(0.4, 0, 0.2, 1)',
                'ds-smooth': 'cubic-bezier(0.4, 0, 0.2, 1)',
            },
            transitionDuration: {
                'ds-fast': '150ms', 'ds-smooth': '250ms',
            },
        },
    },
    plugins: [tailwindcssAnimate],
}
