import {Component, type ErrorInfo, type ReactNode} from 'react';
import DsButton from './DsButton';

interface ErrorBoundaryProps {
    children: ReactNode;
}

interface ErrorBoundaryState {
    hasError: boolean;
    message: string;
}

/**
 * 全局渲染异常兜底（Phase 6-E）。渲染期异常不白屏，展示错误提示 + 刷新。
 * 包裹在 Layout 内容区外层；配合 antd ConfigProvider 主题。
 */
export default class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
    state: ErrorBoundaryState = {hasError: false, message: ''};

    static getDerivedStateFromError(error: unknown): ErrorBoundaryState {
        return {hasError: true, message: error instanceof Error ? error.message : String(error)};
    }

    componentDidCatch(error: Error, info: ErrorInfo) {
        // 只记录堆栈，不弹 toast（避免无限循环）
        console.error('[ErrorBoundary]', error, info.componentStack);
    }

    private handleReload = () => {
        window.location.reload();
    };

    render() {
        if (!this.state.hasError) {
            return this.props.children;
        }
        return (
            <div className="flex items-center justify-center min-h-[60vh]">
                <div
                    className="bg-ds-bg-surface rounded-ds-md shadow-ds-md border border-ds-border-subtle p-ds-8 max-w-[480px] text-center">
                    <h2 className="text-ds-subhead text-ds-text-primary mb-ds-2">页面渲染出错了</h2>
                    <p className="text-ds-body text-ds-text-secondary mb-ds-1">界面遇到了一个意外错误，请刷新重试。</p>
                    <p className="text-ds-caption text-ds-text-muted break-all mb-ds-5">{this.state.message}</p>
                    <DsButton onClick={this.handleReload}>刷新页面</DsButton>
                </div>
            </div>
        );
    }
}
