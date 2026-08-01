import {extendTailwindMerge} from 'tailwind-merge';

/**
 * 合并 Tailwind 类名：后传入的冲突类覆盖前者。
 * 注册了 ds-* 自定义 spacing（见 tailwind.config.js），
 * 否则 pl-ds-2 这类类 tailwind-merge 不认识，无法与 pl-ds-3 判冲突。
 */
export const cn = extendTailwindMerge({
    extend: {
        theme: {
            spacing: ['ds-1', 'ds-2', 'ds-3', 'ds-4', 'ds-5', 'ds-6', 'ds-8', 'ds-10', 'ds-12'],
        },
    },
});
