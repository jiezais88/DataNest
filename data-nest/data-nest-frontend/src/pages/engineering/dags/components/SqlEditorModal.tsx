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
//   execution result area（标签页面板：日志 / 结果，顶部状态栏汇总计数与总耗时）
//   footer (Cancel / Save)
//
// Save semantics: onSave(sql) returns the raw editor content; parent decides
// what to do with it (write back to RFNodeData.sqlContent).
// Run Test: hits backend /api/engineering/dev/sql-preview, renders per-statement
// results inline. Does NOT modify editor content; pre-execution only.
// onTested: 运行测试结束后把整体结果（全部成功=PASSED，否则 FAILED）回传父组件，
// 父组件写回节点 data.lastTestStatus 驱动画布状态点。

import {useEffect, useRef, useState} from 'react';
import {Select, Spin, Tabs} from 'antd';
import {HiChevronRight, HiOutlineCheckCircle, HiOutlineXCircle} from 'react-icons/hi2';
import DsButton from '@/components/DsButton';
import DsModal from '@/components/DsModal';
import '@/lib/monacoSetup';
import Editor, {type OnMount} from '@monaco-editor/react';
import {previewSql} from '@/pages/engineering/dags/api';
import type {DagParameter, SqlStatementResult} from '@/pages/engineering/dags/types';
import {formatDuration} from '@/utils/format';
import {getErrorMessage} from '@/utils/error';

interface SqlEditorModalProps {
    open: boolean;
    onClose: () => void;
    initialSql?: string;
    initialNodeName?: string;
    onSave: (sql: string, nodeName: string) => void;
    /** 运行测试结束回调：全部语句成功=PASSED，否则 FAILED（含请求失败/无语句） */
    onTested?: (status: 'PASSED' | 'FAILED') => void;
    title?: string;
    datasourceId?: number;
    /** Sprint 4：当前 DAG 参数草稿（未保存 DAG 时也能替换参数） */
    dagParams?: DagParameter[];
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
                                           onTested,
                                           title,
                                           datasourceId,
                                           dagParams,
                                           readOnly = false,
                                       }: SqlEditorModalProps) {
    const [nodeName, setNodeName] = useState(initialNodeName);
    const [sql, setSql] = useState(initialSql);
    const [running, setRunning] = useState(false);
    const [results, setResults] = useState<SqlStatementResult[] | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [cursorLine, setCursorLine] = useState(1);
    const [cursorCol, setCursorCol] = useState(1);
    // 结果面板：当前 tab、失败语句错误展开状态、结果 tab 选中的语句
    const [activeTab, setActiveTab] = useState<'log' | 'result'>('log');
    const [expandedErrors, setExpandedErrors] = useState<Record<number, boolean>>({});
    const [selectedQueryIdx, setSelectedQueryIdx] = useState(0);

    const editorRef = useRef<Parameters<OnMount>[0] | null>(null);

    // 重置：当 open 切换时同步 initialSql / initialNodeName（避免脏数据）
    useEffect(() => {
        if (open) {
            setSql(initialSql);
            setNodeName(initialNodeName);
            setResults(null);
            setError(null);
            setActiveTab('log');
            setExpandedErrors({});
            setSelectedQueryIdx(0);
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
        setActiveTab('log');
        setExpandedErrors({});
        setSelectedQueryIdx(0);
        try {
            // Sprint 4：把当前 DAG 参数草稿传给预览接口，未保存 DAG 也能替换 ${param}
            const paramMap = (dagParams || []).reduce<Record<string, unknown>>((acc, p) => {
                if (p.paramName) acc[p.paramName] = p.defaultValue ?? '';
                return acc;
            }, {});
            const resp = await previewSql(sql, datasourceId, paramMap);
            setResults(resp.statements);
            // 全部语句成功才算 PASSED；空结果（未检测到语句）视为 FAILED
            const allOk = resp.statements.length > 0 && resp.statements.every(s => s.status === 'SUCCESS');
            onTested?.(allOk ? 'PASSED' : 'FAILED');
        } catch (e) {
            setError(getErrorMessage(e, 'Request failed'));
            // 请求本身失败也意味着本次测试没有通过，避免残留过期的 PASSED 状态点
            onTested?.('FAILED');
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

    // ─────────── 结果面板派生数据 ───────────
    const successCount = results?.filter(r => r.status === 'SUCCESS').length ?? 0;
    const failedCount = (results?.length ?? 0) - successCount;
    // 总耗时：仅对后端返回了 durationMs 的语句求和；一个都没有时不显示（后端未上新字段也不报错）
    const durations = (results ?? []).map(r => r.durationMs).filter((d): d is number => d != null);
    const totalDurationMs = durations.length > 0 ? durations.reduce((a, b) => a + b, 0) : undefined;
    // 结果 tab：成功的 QUERY 语句（含 0 行，columns/rows 可能为空数组）
    const queryResults = (results ?? []).filter(
        r => r.status === 'SUCCESS' && r.type === 'QUERY',
    );
    const currentQueryIdx = Math.min(selectedQueryIdx, Math.max(queryResults.length - 1, 0));
    const currentQuery = queryResults[currentQueryIdx];
    // 结果 tab：成功的 DML/DDL/UNKNOWN 语句，展示摘要卡片
    const summaryResults = (results ?? []).filter(
        r => r.status === 'SUCCESS' && (r.type === 'DML' || r.type === 'DDL' || r.type === 'UNKNOWN'),
    );

    const toggleError = (idx: number) => {
        setExpandedErrors(prev => ({...prev, [idx]: !prev[idx]}));
    };

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
                    <label
                        className="block text-ds-caption font-bold uppercase tracking-wider text-ds-text-secondary mb-1">
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
                        loading={running}
                        title={readOnly ? '只读模式：您没有编辑权限' : undefined}
                    >
                        ▶ 运行测试
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
                            padding: {top: 8, bottom: 8},
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

                {/* running indicator */}
                {running && (
                    <div
                        className="border border-ds-border-subtle rounded-ds-sm py-ds-6 flex items-center justify-center gap-ds-2 text-ds-small text-ds-text-secondary">
                        <Spin size="small"/>
                        执行中...
                    </div>
                )}

                {/* execution result：标签页面板（日志 / 结果） */}
                {!running && results && results.length > 0 && (
                    <div className="border border-ds-border-subtle rounded-ds-sm overflow-hidden">
                        {/* 状态栏：语句总数 + 成功/失败计数 + 总耗时 */}
                        <div
                            className="px-ds-4 py-ds-2 bg-ds-bg-hover text-ds-caption font-semibold flex items-center gap-ds-3">
                            <span className="text-ds-text-primary">共 {results.length} 条语句</span>
                            <span className="text-ds-success flex items-center gap-ds-1">
                                <HiOutlineCheckCircle size={14}/> {successCount} 成功
                            </span>
                            <span
                                className={failedCount > 0 ? 'text-ds-danger flex items-center gap-ds-1' : 'text-ds-text-muted flex items-center gap-ds-1'}>
                                <HiOutlineXCircle size={14}/> {failedCount} 失败
                            </span>
                            {totalDurationMs != null && (
                                <span className="text-ds-text-muted font-normal">
                                    总耗时 {formatDuration(totalDurationMs)}
                                </span>
                            )}
                        </div>
                        <Tabs
                            size="small"
                            activeKey={activeTab}
                            onChange={k => setActiveTab(k as 'log' | 'result')}
                            className="px-ds-4"
                            items={[
                                {
                                    key: 'log',
                                    label: '日志',
                                    children: (
                                        <div className="pb-ds-2">
                                            {results.map((r, idx) => {
                                                const stmtType = (r.stmt || '').trim().split(/\s+/)[0]?.toUpperCase() || r.type;
                                                const firstLine = (r.stmt || '').trim().split('\n')[0] || '';
                                                const hasError = r.status === 'FAILED' && !!r.error;
                                                const expanded = !!expandedErrors[idx];
                                                return (
                                                    <div
                                                        key={idx}
                                                        className="border-b border-ds-border-subtle last:border-b-0 py-ds-2"
                                                    >
                                                        <div className="flex items-center gap-ds-2">
                                                            <span className="shrink-0 flex items-center">
                                                                {r.status === 'SUCCESS'
                                                                    ? <HiOutlineCheckCircle size={14}
                                                                                            className="text-ds-success"/>
                                                                    : <HiOutlineXCircle size={14}
                                                                                        className="text-ds-danger"/>}
                                                            </span>
                                                            <span
                                                                className="shrink-0 font-mono text-ds-caption font-semibold px-1.5 py-0.5 rounded bg-ds-bg-hover text-ds-text-secondary">
                                                                {stmtType}
                                                            </span>
                                                            <span
                                                                className="flex-1 truncate font-mono text-ds-small text-ds-text-secondary"
                                                                title={r.stmt}>
                                                                {firstLine}
                                                            </span>
                                                            {r.durationMs != null && (
                                                                <span
                                                                    className="shrink-0 text-ds-caption text-ds-text-muted">
                                                                    {formatDuration(r.durationMs)}
                                                                </span>
                                                            )}
                                                            {r.status === 'SUCCESS' && (
                                                                <span
                                                                    className="shrink-0 text-ds-caption text-ds-text-muted">
                                                                    {r.type === 'QUERY'
                                                                        ? `返回 ${r.rowCount ?? 0} 行`
                                                                        : `影响 ${r.rowCount ?? 0} 行`}
                                                                </span>
                                                            )}
                                                            {hasError && (
                                                                <button
                                                                    className="shrink-0 p-0.5 rounded text-ds-text-muted hover:text-ds-danger transition-colors"
                                                                    title={expanded ? '收起错误详情' : '展开错误详情'}
                                                                    onClick={() => toggleError(idx)}
                                                                >
                                                                    <HiChevronRight
                                                                        size={12}
                                                                        className={`transition-transform ${expanded ? 'rotate-90' : ''}`}
                                                                    />
                                                                </button>
                                                            )}
                                                        </div>
                                                        {hasError && expanded && (
                                                            <pre
                                                                className="mt-ds-2 text-ds-caption text-ds-danger font-mono whitespace-pre-wrap break-all bg-ds-danger/5 rounded-ds-sm p-ds-2 m-0">
                                                                {r.error}
                                                            </pre>
                                                        )}
                                                    </div>
                                                );
                                            })}
                                        </div>
                                    ),
                                },
                                {
                                    key: 'result',
                                    label: '结果',
                                    children: queryResults.length === 0 && summaryResults.length === 0 ? (
                                        <div className="text-ds-small text-ds-text-muted text-center py-ds-6">
                                            本次执行没有返回结果集
                                        </div>
                                    ) : (
                                        <div className="pb-ds-4 space-y-ds-4">
                                            {/* QUERY 结果表格 */}
                                            {queryResults.length > 0 && (
                                                <div>
                                                    {queryResults.length > 1 && (
                                                        <Select
                                                            size="small"
                                                            value={currentQueryIdx}
                                                            onChange={v => setSelectedQueryIdx(v)}
                                                            className="mb-ds-2"
                                                            options={queryResults.map((q, i) => ({
                                                                value: i,
                                                                label: `语句 ${(results ?? []).indexOf(q) + 1} · ${(q.stmt || '').trim().split(/\s+/)[0]?.toUpperCase() || q.type}`,
                                                            }))}
                                                        />
                                                    )}
                                                    {currentQuery && (
                                                        <>
                                                            <div
                                                                className="border border-ds-border-subtle rounded-ds-sm overflow-auto max-h-60">
                                                                <table className="w-full text-left">
                                                                    <thead className="bg-ds-bg-hover sticky top-0">
                                                                    <tr>
                                                                        {(currentQuery.columns || []).map((col) => (
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
                                                                    {(currentQuery.rows || []).map((row, ri) => (
                                                                        <tr
                                                                            key={ri}
                                                                            className="border-t border-ds-border-subtle hover:bg-ds-bg-hover"
                                                                        >
                                                                            {(currentQuery.columns || []).map((col, ci) => (
                                                                                <td
                                                                                    key={col}
                                                                                    className="px-ds-2 py-ds-1 text-ds-caption text-ds-text-secondary whitespace-nowrap"
                                                                                >
                                                                                    {row[ci] === null || row[ci] === undefined
                                                                                        ? <span
                                                                                            className="text-ds-text-muted italic">NULL</span>
                                                                                        : String(row[ci])}
                                                                                </td>
                                                                            ))}
                                                                        </tr>
                                                                    ))}
                                                                    </tbody>
                                                                </table>
                                                            </div>
                                                            <div className="mt-ds-2 text-ds-caption text-ds-text-muted">
                                                                {currentQuery.columns && currentQuery.columns.length === 0
                                                                    ? '执行成功，返回 0 行'
                                                                    : currentQuery.truncated
                                                                        ? `仅展示前 ${(currentQuery.rows || []).length} 行（结果集已截断）`
                                                                        : `共 ${currentQuery.rowCount ?? (currentQuery.rows || []).length} 行`}
                                                            </div>
                                                        </>
                                                    )}
                                                </div>
                                            )}

                                            {/* DML/DDL 摘要卡片 */}
                                            {summaryResults.length > 0 && (
                                                <div className="space-y-ds-2">
                                                    {summaryResults.map((r, idx) => {
                                                        const firstLine = (r.stmt || '').trim().split('\n')[0] || '';
                                                        return (
                                                            <div
                                                                key={idx}
                                                                className="border border-ds-border-subtle rounded-ds-sm p-ds-3 bg-ds-bg-surface"
                                                            >
                                                                <div
                                                                    className="flex items-center justify-between gap-ds-2">
                                                                    <span
                                                                        className="font-mono text-ds-caption font-semibold px-1.5 py-0.5 rounded bg-ds-bg-hover text-ds-text-secondary">
                                                                        {r.type}
                                                                    </span>
                                                                    {r.durationMs != null && (
                                                                        <span
                                                                            className="text-ds-caption text-ds-text-muted">
                                                                            {formatDuration(r.durationMs)}
                                                                        </span>
                                                                    )}
                                                                </div>
                                                                <div
                                                                    className="mt-ds-1 font-mono text-ds-small text-ds-text-secondary truncate"
                                                                    title={r.stmt}>
                                                                    {firstLine}
                                                                </div>
                                                                <div
                                                                    className="mt-ds-2 text-ds-small text-ds-text-primary">
                                                                    {r.type === 'DML'
                                                                        ? `影响 ${r.rowCount ?? 0} 行`
                                                                        : r.type === 'DDL'
                                                                            ? 'DDL 执行成功'
                                                                            : `执行成功${r.rowCount != null ? `（影响 ${r.rowCount} 行）` : ''}`}
                                                                </div>
                                                            </div>
                                                        );
                                                    })}
                                                </div>
                                            )}
                                        </div>
                                    ),
                                },
                            ]}
                        />
                    </div>
                )}

                {/* empty result hint */}
                {!running && results && results.length === 0 && (
                    <div className="text-ds-small text-ds-text-muted text-center py-ds-4">
                        未检测到 SQL 语句，请输入后重试。
                    </div>
                )}
            </div>
        </DsModal>
    );
}
