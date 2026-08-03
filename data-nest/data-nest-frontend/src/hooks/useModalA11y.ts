import {useEffect, useRef} from 'react';

/**
 * 弹窗/抽屉可访问性（Phase 7-O）：
 * - Esc 关闭
 * - 打开时聚焦面板内首个可聚焦元素
 * - Tab 焦点圈定在面板内（focus trap）
 * - body 滚动锁定
 */
const FOCUSABLE_SELECTOR =
    'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

function getFocusable(container: HTMLElement): HTMLElement[] {
    return Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
        .filter(el => el.offsetParent !== null || el === document.activeElement);
}

type PanelRef = { current: HTMLElement | null };

export function useModalA11y(open: boolean, onClose: () => void, panelRef: PanelRef) {
    const onCloseRef = useRef(onClose);
    useEffect(() => {
        onCloseRef.current = onClose;
    });

    useEffect(() => {
        if (!open) return;

        const panel = panelRef.current;
        const handleKey = (e: KeyboardEvent) => {
            if (e.key === 'Escape') {
                e.stopPropagation();
                onCloseRef.current();
                return;
            }
            if (e.key === 'Tab' && panel) {
                const focusables = getFocusable(panel);
                if (focusables.length === 0) {
                    e.preventDefault();
                    return;
                }
                const first = focusables[0];
                const last = focusables[focusables.length - 1];
                const active = document.activeElement;
                const inside = panel.contains(active);
                if (e.shiftKey && (!inside || active === first)) {
                    e.preventDefault();
                    last.focus();
                } else if (!e.shiftKey && (!inside || active === last)) {
                    e.preventDefault();
                    first.focus();
                }
            }
        };

        document.addEventListener('keydown', handleKey, true);
        const prevOverflow = document.body.style.overflow;
        document.body.style.overflow = 'hidden';

        if (panel) {
            const focusables = getFocusable(panel);
            (focusables[0] || panel).focus();
        }

        return () => {
            document.removeEventListener('keydown', handleKey, true);
            document.body.style.overflow = prevOverflow;
        };
    }, [open, panelRef]);
}
