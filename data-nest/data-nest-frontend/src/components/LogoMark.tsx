/**
 * DataNest 品牌 Logo（嵌套六边形 = 数据"蜂巢/网络"母题）。
 * 与 public/favicon.svg 同源，供侧边栏、登录页等场景统一使用。
 */
interface LogoMarkProps {
    size?: number;
    className?: string;
}

export default function LogoMark({size = 28, className = ''}: LogoMarkProps) {
    return (
        <svg
            width={size}
            height={size}
            viewBox="0 0 32 32"
            className={className}
            role="img"
            aria-label="DataNest"
        >
            <defs>
                <linearGradient id="datanest-logo-grad" x1="0" y1="0" x2="1" y2="1">
                    <stop offset="0%" stopColor="#4f46e5"/>
                    <stop offset="100%" stopColor="#818cf8"/>
                </linearGradient>
            </defs>
            {/* outermost hexagon: light purple thin outline */}
            <polygon points="16,2 28.12,9 28.12,23 16,30 3.88,23 3.88,9"
                     fill="none" stroke="#a5b4fc" strokeWidth="1.2" strokeLinejoin="round"/>
            {/* middle hexagon: indigo medium outline */}
            <polygon points="16,6 24.66,11 24.66,21 16,26 7.34,21 7.34,11"
                     fill="none" stroke="#6366f1" strokeWidth="1.6" strokeLinejoin="round"/>
            {/* innermost hexagon: solid indigo-purple gradient */}
            <polygon points="16,10 21.20,13 21.20,19 16,22 10.80,19 10.80,13"
                     fill="url(#datanest-logo-grad)" stroke="none"/>
        </svg>
    );
}
