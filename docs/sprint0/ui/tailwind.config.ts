import type {Config} from 'tailwindcss'

const config: Config = {
    content: ['./index.html', './src/**/*.{ts,tsx}'],
    theme: {
        extend: {
            colors: {
                // Background layers
                'ds-bg-root': '#f7f8fa',
                'ds-bg-surface': '#ffffff',
                'ds-bg-elevated': '#ffffff',
                'ds-bg-hover': '#f1f3f6',

                // Borders
                'ds-border-subtle': '#e2e6ed',
                'ds-border-strong': '#cdd3dc',

                // Text
                'ds-text-primary': '#0f172a',
                'ds-text-secondary': '#475569',
                'ds-text-muted': '#94a3b8',

                // Accent
                'ds-accent': {DEFAULT: '#4f46e5', hover: '#4338ca', light: '#eef2ff', glow: 'rgba(79,70,229,0.12)'},

                // Semantic
                'ds-danger': {DEFAULT: '#dc2626', hover: '#b91c1c', light: '#fef2f2'},
                'ds-success': {DEFAULT: '#16a34a', light: '#f0fdf4'},
                'ds-warning': {DEFAULT: '#d97706', light: '#fffbeb'},
            },

            fontFamily: {
                sans: ["'Inter'", '-apple-system', 'BlinkMacSystemFont', "'Segoe UI'", 'sans-serif'],
            },

            fontSize: {
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
                // 4px base system: 1=4px, 2=8px, 3=12px, ...
                'ds-1': '4px', 'ds-2': '8px', 'ds-3': '12px',
                'ds-4': '16px', 'ds-5': '20px', 'ds-6': '24px',
                'ds-8': '32px', 'ds-10': '40px', 'ds-12': '48px',
            },

            borderRadius: {
                'ds-sm': '8px',
                'ds-md': '12px',
                'ds-lg': '16px',
                'ds-full': '100px',
            },

            boxShadow: {
                'ds-xs': '0 1px 2px rgba(0,0,0,0.04)',
                'ds-sm': '0 1px 3px rgba(0,0,0,0.06), 0 1px 2px rgba(0,0,0,0.04)',
                'ds-md': '0 4px 6px rgba(0,0,0,0.04), 0 2px 4px rgba(0,0,0,0.03)',
                'ds-lg': '0 10px 15px rgba(0,0,0,0.05), 0 4px 6px rgba(0,0,0,0.03)',
                'ds-xl': '0 20px 25px rgba(0,0,0,0.06), 0 10px 10px rgba(0,0,0,0.03)',
            },

            zIndex: {
                'ds-elevated': '100',
                'ds-overlay': '200',
                'ds-dialog': '300',
            },

            transitionTimingFunction: {
                'ds-fast': 'cubic-bezier(0.4, 0, 0.2, 1)',
                'ds-smooth': 'cubic-bezier(0.4, 0, 0.2, 1)',
            },
            transitionDuration: {
                'ds-fast': '150ms',
                'ds-smooth': '250ms',
            },
        },
    },
    plugins: [],
}

export default config
