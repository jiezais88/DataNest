/**
 * monaco-editor 0.56 的 package.json exports 只为根入口声明了 types，
 * 按需子路径 'monaco-editor/editor/editor.api' 运行时存在（exports map "./*" →
 * "./esm/vs/*.js"）但 TS 找不到类型。根入口的 index.d.ts 本身就是
 * editor.api 的全量 re-export，这里直接桥接。
 */
declare module 'monaco-editor/editor/editor.api' {
    export * from 'monaco-editor';
}
