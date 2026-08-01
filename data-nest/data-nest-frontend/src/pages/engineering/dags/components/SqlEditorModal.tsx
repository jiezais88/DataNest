// Sprint 3 SQL Task Editor Modal
// 900x600 dark Monaco editor + toolbar + result area
// See DESIGN 4.25 and FRONTEND-UI-GAP-ANALYSIS 1.2
//
// Structure:
//   header (title + node-name input)
//   toolbar (Run Test / Select All / Undo / Redo)
//   NOTE: no "Format" button — Monaco ships no SQL formatter, so
//   editor.action.formatDocument would be a silent no-op.
//   monaco editor (300px, vs-dark, sql)
//   status bar (row/col from cursor position)
//   execution result area (per-statement success/fail detail)
//   footer (Cancel / Save)
//
// Save semantics: onSave(sql) returns the raw editor content; parent decides
// what to do with it (write back to RFNodeData.sqlContent).
// Run Test: hits backend /api/engineering/dev/sql-preview, renders per-statement
// results inline. Does NOT modify editor content; pre-execution only.

import {useEffect, useRef, useState} from 'react';
import DsButton from '../../../../components/DsButton';
import DsModal from '../../../../components/DsModal';
import '../../../../lib/monacoSetup';
import Editor, {type OnMount} from '@monaco-editor/react';
import {previewSql} from '../api';
import type {SqlStatementResult} from '../types';
import {getErrorMessage} from '../../../../utils/error';

interface SqlEditorModalProps {
    open: boolean;
    onClose: () => void;
    initialSql?: string;
    initialNodeName?: string;
    onSave: (sql: string, nodeName: string) => void;
    title?: string;
    datasourceId?: number;
    /** Sprint 3 权限：true 时禁用「运行测试」「保存」按钮（Sprint 3 差距分析 §1.14 + §1.2） */
    readOnly?: boolean;
}

const DEFAULT_TITLE = '编辑 SQL 任务';

export default function SqlEditorModal({
                                           open,
                                           onClose,
                                           initialSql = '',
                                           initialNodeName = '',
                                           onSave,
                                           title,
                                           datasourceId,
                                           readOnly = false,
                                       }: SqlEditorModalProps) {
    const [nodeName, setNodeName] = useState(initialNodeName);
    const [sql, setSql] = useState(initialSql);
    const [running, setRunning] = useState(false);
    const [results, setResults] = useState<SqlStatementResult[] | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [cursorLine, setCursorLine] = useState(1);
    const [cursorCol, setCursorCol] = useState(1);

    const editorRef = useRef<Parameters<OnMount>[0] | null>(null);

    // 重置：当 open 切换时同步 initialSql / initialNodeName（避免脏数据）
    useEffect(() => {
        if (open) {
            setSql(initialSql);
            setNodeName(initialNodeName);
            setResults(null);
            setError(null);
        }
    }, [open, initialSql, initialNodeName]);

    const handleEditorMount: OnMount = (editor) => {
        editorRef.current = editor;
        editor.onDidChangeCursorPosition((e) => {
            setCursorLine(e.position.lineNumber);
            setCursorCol(e.position.column);
        });
    };

    const handleRunTest = async () => {
        if (running) return;
        setRunning(true);
        setError(null);
        setResults(null);
        try {
            const resp = await previewSql(sql, datasourceId);
            setResults(resp.statements);
        } catch (e) {
            setError(getErrorMessage(e, 'Request failed'));
        } finally {
            setRunning(false);
        }
    };

    const handleSelectAll = () => {
        const ed = editorRef.current;
        if (!ed) return;
        const model = ed.getModel();
        if (!model) return;
        // 不用 editor.action.selectAll：编辑器未聚焦时该 action 可能空跑，
        // 直接 setSelection 全量范围 + focus 行为确定（探针验证）
        ed.focus();
        ed.setSelection(model.getFullModelRange());
    };

    const handleUndo = () => {
        const ed = editorRef.current;
        if (!ed) return;
        ed.trigger('keyboard', 'undo', null);
    };

    const handleRedo = () => {
        const ed = editorRef.current;
        if (!ed) return;
        ed.trigger('keyboard', 'redo', null);
    };

    const handleSave = () => {
        onSave(sql, nodeName.trim() || 'SQL 节点');
    };

    const hasQueryResult = results?.some((r) => r.status === 'SUCCESS' && r.type === 'QUERY' && r.rows && r.rows.length > 0);

    return (
        <DsModal
            open={open}
            onClose={onClose}
            title={title || DEFAULT_TITLE}
            width="w-[900px] max-w-[96vw] h-[620px]"
            bordered
            // 运行中禁止通过遮罩/关闭按钮退出，避免测试请求中途丢状态
            maskClosable={!running}
            closable={!running}
            // 固定 620px 高度下让内容区撑满剩余空间并内部滚动（基座默认 max-h 不适配固定高度）
            bodyMaxHeight="flex-1 min-h-0"
            footer={
                <>
                    <DsButton
                        variant="secondary"
                        onClick={onClose}
                        disabled={running}
                    >
                        取消
                    </DsButton>
                    <DsButton
                        variant="primary"
                        onClick={handleSave}
                        disabled={readOnly || running || !sql.trim()}
                        title={readOnly ? '只读模式：您没有编辑权限' : undefined}
                    >
                        保存
                    </DsButton>
                </>
            }
        >
            <div className="space-y-ds-4">
                {/* node name */}
                <div>
                    <label className="block text-[12px] font-bold uppercase tracking-wider text-ds-text-secondary mb-1">
                        节点名称 <span className="text-ds-danger">*</span>
                    </label>
                    <input
                        value={nodeName}
                        onChange={(e) => setNodeName(e.target.value)}
                        placeholder="如：订单数据清洗"
                        className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors duration-ds-fast"
                    />
                </div>

                {/* section title */}
                <div className="text-ds-caption text-ds-text-muted font-bold uppercase tracking-wider">
                    SQL 编辑器
                </div>

                {/* toolbar */}
                <div className="flex items-center gap-ds-2">
                    <DsButton
                        variant="primary"
                        onClick={handleRunTest}
                        disabled={readOnly || running || !sql.trim()}
                        title={readOnly ? '只读模式：您没有编辑权限' : undefined}
                    >
                        {running ? '运行中...' : '▶ 运行测试'}
                    </DsButton>
                    <DsButton
                        variant="secondary"
                        onClick={handleSelectAll}
                    >
                        全选
                    </DsButton>
                    <DsButton
                        variant="secondary"
                        onClick={handleUndo}
                    >
                        撤销
                    </DsButton>
                    <DsButton
                        variant="secondary"
                        onClick={handleRedo}
                    >
                        重做
                    </DsButton>
                </div>

                {/* monaco editor */}
                <div className="border border-ds-border-subtle rounded-ds-sm overflow-hidden">
                    <Editor
                        height="300px"
                        defaultLanguage="sql"
                        theme="vs-dark"
                        value={sql}
                        onChange={(v) => setSql(v || '')}
                        onMount={handleEditorMount}
                        options={{
                            fontSize: 13,
                            minimap: {enabled: false},
                            wordWrap: 'on',
                            lineNumbers: 'on',
                            scrollBeyondLastLine: false,
                            automaticLayout: true,
                            tabSize: 2,
                        }}
                    />
                </div>

                {/* status bar */}
                <div className="text-ds-caption text-ds-text-muted font-mono">
                    行: {cursorLine} &nbsp; 列: {cursorCol}
                </div>

                {/* error display */}
                {error && (
                    <div
                        className="border border-ds-danger/30 bg-ds-danger/5 text-ds-danger rounded-ds-sm p-ds-3 text-ds-small">
                        {error}
                    </div>
                )}

                {/* execution result */}
                {results && results.length > 0 && (
                    <div className="border border-ds-border-subtle rounded-ds-sm overflow-hidden">
                        <div
                            className="px-ds-4 py-ds-2 bg-ds-bg-hover text-ds-caption text-ds-text-primary font-semibold">
                            执行结果（{results.length} 条语句）
                        </div>
                        <div className="p-ds-4 space-y-ds-2">
                            {results.map((r, idx) => {
                                // 原型样式：左侧语句类型（INSERT/CREATE/SELECT…），右侧 ✅成功/❌失败，不显示 SQL 原文
                                const stmtType = (r.stmt || '').trim().split(/\s+/)[0]?.toUpperCase() || r.type;
                                return (
                                    <div
                                        key={idx}
                                        className="border-b border-ds-border-subtle last:border-b-0 py-ds-2"
                                    >
                                        <div className="flex items-center justify-between gap-ds-3">
                                            <span
                                                className="font-mono text-ds-small font-semibold text-ds-text-secondary">
                                                {stmtType}
                                            </span>
                                            <span className={`text-ds-small font-semibold ${
                                                r.status === 'SUCCESS' ? 'text-ds-success' : 'text-ds-danger'
                                            }`}>
                                                {r.status === 'SUCCESS'
                                                    ? `✅ 成功${r.rowCount > 0 ? `，影响 ${r.rowCount} 行` : ''}`
                                                    : '❌ 失败'}
                                            </span>
                                        </div>
                                        {r.error && (
                                            <div className="mt-1 text-ds-caption text-ds-danger font-mono break-all">
                                                {r.error}
                                            </div>
                                        )}

                                        {/* inline query result table */}
                                        {r.status === 'SUCCESS' && r.type === 'QUERY' && r.columns && r.rows && r.rows.length > 0 && (
                                            <div
                                                className="mt-ds-2 border border-ds-border-subtle rounded-ds-sm overflow-auto max-h-60">
                                                <table className="w-full text-left">
                                                    <thead className="bg-ds-bg-hover sticky top-0">
                                                    <tr>
                                                        {r.columns.map((col) => (
                                                            <th
                                                                key={col}
                                                                className="px-ds-2 py-ds-1 text-ds-caption text-ds-text-primary font-semibold whitespace-nowrap"
                                                            >
                                                                {col}
                                                            </th>
                                                        ))}
                                                    </tr>
                                                    </thead>
                                                    <tbody>
                                                    {r.rows.map((row, ri) => (
                                                        <tr
                                                            key={ri}
                                                            className="border-t border-ds-border-subtle"
                                                        >
                                                            {r.columns!.map((col, ci) => (
                                                                <td
                                                                    key={col}
                                                                    className="px-ds-2 py-ds-1 text-ds-caption text-ds-text-secondary whitespace-nowrap"
                                                                >
                                                                    {row[ci] === null || row[ci] === undefined
                                                                        ? 'NULL'
                                                                        : String(row[ci])}
                                                                </td>
                                                            ))}
                                                        </tr>
                                                    ))}
                                                    </tbody>
                                                </table>
                                            </div>
                                        )}
                                    </div>
                                );
                            })}
                        </div>
                    </div>
                )}

                {/* empty result hint */}
                {results && results.length === 0 && (
                    <div className="text-ds-small text-ds-text-muted text-center py-ds-4">
                        未检测到 SQL 语句，请输入后重试。
                    </div>
                )}

                {/* unused but type-checked */}
                {hasQueryResult === false && <div className="hidden"/>}
            </div>
        </DsModal>
    );
}
