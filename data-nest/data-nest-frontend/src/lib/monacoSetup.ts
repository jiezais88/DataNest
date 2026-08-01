// Bundle Monaco locally instead of loading from jsdelivr CDN.
// The default @monaco-editor/react loader fetches loader.js from the CDN at
// runtime; in environments where the CDN is unreachable this leaves the
// editor broken (blank editor, dead keybindings). Importing monaco-editor
// here and handing it to loader.config({ monaco }) makes the editor fully
// self-contained.
//
// 按需加载：editor.api 是完整编辑器本体（含查找/折叠等全部 contrib，
// 不含任何语言），语言只注册 SQL（其定义 sql.js 由 loader 动态 import，
// 单独切 chunk）。不要改回裸 'monaco-editor'（= editor.main，会打进
// 全部语言，主包体积翻倍）。
// NOTE: monaco-editor 0.56 exports map rewrites "./*" -> "./esm/vs/*.js",
// so the bare specifier must NOT include the "esm/vs" prefix.
import * as monaco from 'monaco-editor/editor/editor.api';
import 'monaco-editor/languages/definitions/sql/register';
import editorWorker from 'monaco-editor/editor/editor.worker?worker';
import {loader} from '@monaco-editor/react';

self.MonacoEnvironment = {
    getWorker: () => new editorWorker(),
};

loader.config({monaco});

// Parity with the CDN AMD loader, which exposes window.monaco — handy for
// debugging and e2e probes (editor.getEditors() etc.).
(globalThis as typeof globalThis & { monaco: typeof monaco }).monaco = monaco;
