// Sprint 10 F1：SQL 查询终端（紧凑 IDE 风格，产品化）。
// 左侧「数据目录」树（SqlTree：sql-console 全部 NORMAL 数据源，库/表走元数据域懒加载，未采集可 inline 立即采集）
// · 点表插入 `SELECT * FROM 库.表 LIMIT 100` · 面包屑路径显示当前上下文（不显示 id）
// · Monaco 编辑器（Ctrl+Enter）· 运行/停止 · 结果卡片化 · CSV/Excel 导出
// · 查询历史 Drawer（顶部按钮 + badge，显示数据源名，回填联动左侧树高亮 + 面包屑更新）。
import {useCallback, useEffect, useRef, useState} from 'react';
import {Table, Tooltip} from 'antd';
import {
    HiOutlineArrowDownTray,
    HiOutlineBolt,
    HiOutlineCheckCircle,
    HiOutlineCircleStack,
    HiOutlineClock,
    HiOutlinePlay,
    HiOutlineShieldExclamation,
    HiOutlineStop,
    HiOutlineTableCells,
    HiOutlineTrash,
    HiOutlineXCircle,
} from 'react-icons/hi2';
import * as monaco from 'monaco-editor/editor/editor.api';
import '@/lib/monacoSetup';
import Editor, {type OnMount} from '@monaco-editor/react';
import {
    cancelQuery,
    clearQueryHistory,
    executeSql,
    exportSqlResult,
    getQueryHistory,
    listSqlDatasources,
} from '@/api/data-service';
import ConfirmDialog from '@/components/ConfirmDialog';
import Drawer from '@/components/Drawer';
import DsButton from '@/components/DsButton';
import DsIconButton from '@/components/DsIconButton';
import DsTableEmpty from '@/components/DsTableEmpty';
import Pagination from '@/components/Pagination';
import usePagedList from '@/hooks/usePagedList';
import {downloadExportBlob} from '@/utils/download';
import {getErrorMessage} from '@/utils/error';
import {formatDateTime, formatDuration, formatNumber} from '@/utils/format';
import {notify} from '@/utils/notify';
import type {SqlDatasource, SqlExecuteResult, SqlQueryHistory} from '@/types/data-service';
import SqlTree, {type SqlTreeContext, type SqlTreeHandle} from './SqlTree';

const DEFAULT_SQL = 'SELECT * FROM 表名 LIMIT 100';

/** 从 SQL 提取第一个表名（导出文件名用）；先剥离注释避免误匹配；取不到用 result */
function extractTableName(sql: string): string {
    const withoutComments = sql.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/--.*$/gm, ' ');
    const m = withoutComments.match(/\b(?:FROM|JOIN|INTO|UPDATE)\s+([`"]?[A-Za-z0-9_.]+[`"]?)/i);
    return m ? m[1].replace(/[`"]/g, '') : 'result';
}

/** 从 SQL 提取引用的表（历史回填联动：匹配第一个 db.table / db.schema.table 或裸表名） */
function extractTableRef(sql: string): {databaseName?: string; schemaName?: string; tableName?: string} {
    const withoutComments = sql.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/--.*$/gm, ' ');
    const m = withoutComments.match(/\b(?:FROM|JOIN|INTO|UPDATE)\s+([`"]?)([\w$]+)(?:\.([\w$]+))?(?:\.([\w$]+))?/i);
    if (!m) return {};
    const [, , first, second, third] = m;
    if (third) return {databaseName: first, schemaName: second, tableName: third};
    if (second) return {databaseName: first, tableName: second};
    return {tableName: first};
}

/** 错误分类（按后端 ErrorCode 语义映射图标与标题，供错误展示面板用） */
function classifyError(message: string): {icon: 'blocked' | 'syntax' | 'timeout' | 'sensitive' | 'failed'; title: string} {
    const m = message ?? '';
    if (m.includes('只读') || m.includes('非只读') || m.includes('SQL 包含')) {
        return {icon: 'blocked', title: '非只读 SQL 已拦截'};
    }
    if (m.includes('语法') || m.toLowerCase().includes('parse')) {
        return {icon: 'syntax', title: 'SQL 语法错误'};
    }
    if (m.includes('超时') || m.includes('已被停止')) {
        return {icon: 'timeout', title: '查询超时或已停止'};
    }
    if (m.includes('机密') || m.includes('分级服务')) {
        return {icon: 'sensitive', title: '机密数据保护'};
    }
    return {icon: 'failed', title: '查询失败'};
}

/** 紧凑 KPI 卡（圆形背景图标 + 大字数字 + 标签，对齐原型 sql-kpi 视觉） */
function KpiItem({label, value, sub, icon: Icon, tone}: {
    label: string;
    value: string;
    sub?: string;
    icon: React.ComponentType<{size?: number; className?: string}>;
    tone: 'accent' | 'success' | 'neutral' | 'warning';
}) {
    const valueClass = tone === 'success' ? 'text-ds-success' : tone === 'accent' ? 'text-ds-accent' : tone === 'warning' ? 'text-ds-warning' : 'text-ds-text-primary';
    const iconWrap = tone === 'accent'
        ? 'bg-ds-accent/10 text-ds-accent'
        : tone === 'success'
            ? 'bg-ds-success/10 text-ds-success'
            : tone === 'warning'
                ? 'bg-ds-warning/10 text-ds-warning'
                : 'bg-ds-bg-hover text-ds-text-secondary';
    return (
        <div className="flex items-center gap-2.5 px-3 py-2 bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md">
            <div className={`w-8 h-8 rounded-full flex items-center justify-center flex-shrink-0 ${iconWrap}`}>
                <Icon size={16}/>
            </div>
            <div className="min-w-0">
                <div className={`text-ds-h5 font-bold tabular-nums leading-none ${valueClass}`}>{value}</div>
                <div className="text-ds-tiny text-ds-text-muted truncate leading-tight mt-1">{label}</div>
                {sub && <div className="text-ds-nano text-ds-text-muted truncate">{sub}</div>}
            </div>
        </div>
    );
}

export default function SqlConsolePage() {
    const [sql, setSql] = useState(DEFAULT_SQL);
    const [running, setRunning] = useState(false);
    const [result, setResult] = useState<SqlExecuteResult | null>(null);
    const [errorMsg, setErrorMsg] = useState<string | null>(null);

    // 左侧树当前选中上下文（面包屑路径用）
    const [context, setContext] = useState<SqlTreeContext | null>(null);

    const editorRef = useRef<Parameters<OnMount>[0] | null>(null);
    const abortRef = useRef<AbortController | null>(null);
    const queryIdRef = useRef('');
    const treeRef = useRef<SqlTreeHandle | null>(null);

    // 当前执行数据源 id（由 SqlTree 选中表/数据源时更新）
    const datasourceIdRef = useRef<string>('-1');

    // sql-console 数据源映射（历史 Drawer 显示数据源名 + 导出文件名）
    const [datasources, setDatasources] = useState<SqlDatasource[]>([]);
    useEffect(() => {
        listSqlDatasources()
            .then(res => setDatasources(res.data ?? []))
            .catch(() => {/* 错误由拦截器统一提示 */});
    }, []);
    const dsNameOf = useCallback((datasourceId?: string) => {
        if (!datasourceId) return '数据源';
        const hit = datasources.find(d => d.id === datasourceId);
        return hit ? (hit.builtin ? 'Doris 数仓' : hit.name) : '数据源';
    }, [datasources]);

    // ============ 查询历史（Drawer） ============
    const [historyOpen, setHistoryOpen] = useState(false);
    const {
        list: historyList,
        total: historyTotal,
        page: historyPage,
        pageSize: historyPageSize,
        loading: historyLoading,
        setPage: setHistoryPage,
        setPageSize: setHistoryPageSize,
        reload: reloadHistory,
    } = usePagedList<Record<string, never>, SqlQueryHistory>({
        fetcher: async ({page, pageSize}) => {
            const res = await getQueryHistory(page, pageSize);
            return {list: res.data.records, total: res.data.total};
        },
        initialQuery: {},
        defaultPageSize: 10,
    });

    const [clearOpen, setClearOpen] = useState(false);
    const [clearing, setClearing] = useState(false);

    const handleClearHistory = async () => {
        setClearing(true);
        try {
            await clearQueryHistory();
            notify.success('查询历史已清空');
            reloadHistory();
        } catch {
            // 错误由拦截器统一提示
        } finally {
            setClearing(false);
            setClearOpen(false);
        }
    };

    const openHistory = useCallback(() => {
        setHistoryOpen(true);
        reloadHistory();
    }, [reloadHistory]);

    // ============ 运行 / 停止 ============
    const runQuery = useCallback(async () => {
        if (running) return;
        const trimmed = sql.trim();
        if (!trimmed) {
            notify.warning('请输入 SQL');
            return;
        }
        setRunning(true);
        setErrorMsg(null);
        setResult(null);

        const queryId = crypto.randomUUID();
        queryIdRef.current = queryId;
        const controller = new AbortController();
        abortRef.current = controller;
        try {
            const res = await executeSql({datasourceId: datasourceIdRef.current ?? '-1', sql: trimmed, queryId}, controller.signal);
            setResult(res.data);
            reloadHistory();
        } catch (e) {
            if (controller.signal.aborted) return; // 用户点击「停止」：不展示错误
            setErrorMsg(getErrorMessage(e, '查询失败'));
        } finally {
            setRunning(false);
            abortRef.current = null;
        }
    }, [running, sql, reloadHistory]);

    const stopQuery = useCallback(() => {
        abortRef.current?.abort();
        const queryId = queryIdRef.current;
        if (queryId) {
            cancelQuery({queryId}).catch(() => {/* 尽力而为，失败忽略 */});
        }
        setRunning(false);
    }, []);

    // Ctrl+Enter 运行时始终取最新 runQuery：Monaco onMount 只在挂载时触发一次。
    const runQueryRef = useRef(runQuery);
    runQueryRef.current = runQuery;

    const handleEditorMount: OnMount = useCallback((editor) => {
        editorRef.current = editor;
        editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => {
            runQueryRef.current();
        });
    }, []);

    // ============ SqlTree 回调 ============
    const handleInsertTable = useCallback((qualified: string) => {
        const stmt = `SELECT * FROM ${qualified} LIMIT 100;`;
        setSql(stmt);
        setTimeout(() => {
            editorRef.current?.focus();
            editorRef.current?.setPosition({lineNumber: 1, column: stmt.length + 1});
        }, 0);
        notify.info(`已插入 ${qualified} 的查询模板`);
    }, []);

    const handleContextChange = useCallback((ctx: SqlTreeContext | null) => {
        datasourceIdRef.current = ctx?.datasourceId ?? '-1';
        setContext(ctx);
    }, []);

    // ============ 历史回填（联动左侧树 + 面包屑） ============
    const fillFromHistory = useCallback(async (item: SqlQueryHistory) => {
        setSql(item.sqlText);
        setHistoryOpen(false);
        if (item.datasourceId) {
            const ref = extractTableRef(item.sqlText);
            const dsId = String(item.datasourceId);
            // 树联动：展开并高亮到对应数据源/库/模式/表
            await treeRef.current?.selectByPath(dsId, ref.databaseName, ref.schemaName, ref.tableName);
            // 面包屑更新
            setContext({
                datasourceId: dsId,
                dsName: dsNameOf(dsId),
                databaseName: ref.databaseName,
                schemaName: ref.schemaName,
                tableName: ref.tableName,
            });
        }
    }, [dsNameOf]);

    // ============ 导出（后端生成文件流，Sprint 10 用户拍板：所有导出走后端） ============
    const exportBaseName = () => {
        const dsName = context?.dsName || '数据源';
        const table = extractTableName(sql);
        const now = new Date();
        const pad = (n: number) => String(n).padStart(2, '0');
        const ts = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}_${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
        return `${dsName}_${table}_${ts}`;
    };

    /** 复用执行链路触发后端导出（相同 SQL/数据源，服务端再跑一次只读校验+敏感度闸门） */
    const exportResult = async (format: 'XLSX' | 'CSV') => {
        if (!result) return;
        try {
            const blob = await exportSqlResult({
                datasourceId: datasourceIdRef.current ?? '-1',
                sql,
                format,
                timeoutSeconds: undefined,
            });
            const ok = await downloadExportBlob(blob, `${exportBaseName()}.${format.toLowerCase()}`);
            if (ok) notify.success(format === 'CSV' ? 'CSV 导出成功' : 'Excel 导出成功');
        } catch (e) {
            notify.error(getErrorMessage(e));
        }
    };

    const exportCsv = () => exportResult('CSV');
    const exportXlsx = () => exportResult('XLSX');

    // ============ 结果表（紧凑 IDE 风格） ============
    const tableColumns = (result?.columns ?? []).map(c => ({
        title: c,
        dataIndex: c,
        key: c,
        ellipsis: true,
        render: (v: unknown) => {
            if (v == null) return <span className="text-ds-text-muted">NULL</span>;
            const text = typeof v === 'object'
                ? String((v as {value?: unknown})?.value ?? JSON.stringify(v))
                : String(v);
            return <span className="font-mono text-ds-tiny break-all">{text}</span>;
        },
    }));
    const tableData = result?.rows ?? [];

    // 面包屑路径（不显示 id，支持 数据源→库→模式→表 四层）
    const crumbs = [
        context?.dsName,
        context?.databaseName,
        context?.schemaName,
        context?.tableName,
    ].filter(Boolean);

    const renderSecurityBanner = () => {
        // 错误信息已由结果区专门错误面板展示（icon + 标题 + 详情），此处不重复横幅
        if (errorMsg) return null;
        if (result && result.confidentialHits > 0) {
            return (
                <div className="px-4 py-2.5 bg-ds-warning/10 border-b border-ds-warning/30 flex items-start gap-2">
                    <HiOutlineXCircle size={14} className="text-ds-warning mt-0.5 flex-shrink-0"/>
                    <span className="text-ds-small text-ds-warning">
                        该查询涉及 {result.confidentialHits} 张机密表，命中机密级保护，相关数据已拦截不返回。
                    </span>
                </div>
            );
        }
        return null;
    };

    return (
        <div className="h-full flex flex-col gap-3 overflow-hidden">
            {/* 页头：标题 + 描述（左）· 操作按钮（右） */}
            <div className="flex-shrink-0 flex items-start gap-3">
                <div className="min-w-0">
                    <div className="flex items-center gap-2">
                        <HiOutlineTableCells size={20} className="text-ds-accent flex-shrink-0"/>
                        <h1 className="text-ds-display text-ds-text-primary whitespace-nowrap">SQL 查询终端</h1>
                    </div>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">
                        在左侧目录选择库表插入查询模板，或手动编写只读 SQL 查询与导出结果。
                    </p>
                </div>
                <div className="ml-auto flex items-center gap-1.5 flex-shrink-0">
                    <DsButton variant="secondary" onClick={openHistory}>
                        <HiOutlineClock size={14}/> 查询历史
                    </DsButton>
                    <Tooltip title="快捷键：Ctrl+Enter">
                        <DsButton variant="primary" onClick={runQuery} disabled={running} loading={running}>
                            <HiOutlinePlay size={14}/> 运行
                        </DsButton>
                    </Tooltip>
                    <DsButton variant="secondary" onClick={stopQuery} disabled={!running}>
                        <HiOutlineStop size={14}/> 停止
                    </DsButton>
                </div>
            </div>

            {/* 当前上下文面包屑（独立导航条：数据源 › 库 › 模式 › 表） */}
            <div className="flex-shrink-0 flex items-center gap-2 px-3 py-1.5 bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md">
                <span className="text-ds-nano text-ds-text-muted uppercase tracking-wide flex-shrink-0">当前上下文</span>
                {crumbs.length > 0 ? (
                    <span className="flex items-center gap-1 min-w-0 overflow-hidden">
                        {crumbs.map((c, i) => (
                            <span key={i} className="flex items-center gap-1 min-w-0">
                                {i > 0 && <span className="text-ds-border-muted flex-shrink-0">›</span>}
                                <span className="truncate max-w-[160px] text-ds-small text-ds-text-secondary">
                                    {c}
                                </span>
                            </span>
                        ))}
                    </span>
                ) : (
                    <span className="text-ds-small text-ds-text-muted">Doris 数仓</span>
                )}
                <span className="ml-auto text-ds-nano text-ds-text-muted flex-shrink-0">执行数据源由当前上下文决定</span>
            </div>

            {/* 主体：左树 + 右编辑器/结果 */}
            <div className="flex-1 min-h-0 flex gap-3">
                {/* 左侧：数据目录树 */}
                <div className="w-[260px] shrink-0 min-h-0 bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md overflow-hidden flex flex-col">
                    <SqlTree ref={treeRef} onInsert={handleInsertTable} onContextChange={handleContextChange}/>
                </div>

                {/* 右侧：编辑器 + 结果 */}
                <div className="flex-1 min-h-0 flex flex-col gap-2.5">
                    {/* Monaco 编辑器 */}
                    <div className="flex-shrink-0 border border-ds-border-subtle rounded-ds-md overflow-hidden bg-[#1e1e1e]">
                        <Editor
                            height="280px"
                            defaultLanguage="sql"
                            theme="vs-dark"
                            value={sql}
                            onChange={(v) => setSql(v || '')}
                            onMount={handleEditorMount}
                            options={{
                                fontSize: 12,
                                minimap: {enabled: false},
                                wordWrap: 'on',
                                lineNumbers: 'on',
                                scrollBeyondLastLine: false,
                                automaticLayout: true,
                                tabSize: 2,
                                glyphMargin: false,
                                folding: true,
                            }}
                        />
                    </div>

                    {/* KPI 条 + 导出（合并一行，紧凑） */}
                    <div className="flex-shrink-0 flex items-center gap-2">
                        <KpiItem label="本次用时" value={result ? formatDuration(result.durationMs) : '-'} icon={HiOutlineBolt} tone="accent"/>
                        <KpiItem label="返回行/上限" value={result ? `${formatNumber(result.rowCount)} / 1000` : '-'} icon={HiOutlineCheckCircle} tone="success"/>
                        <KpiItem label="涉及表" value={result ? `${formatNumber(result.tableCount)} 表` : '-'} icon={HiOutlineCircleStack} tone="neutral"/>
                        <KpiItem
                            label="机密拦截"
                            value={result ? String(result.confidentialHits) : '-'}
                            sub={result && result.confidentialHits === 0 ? '未触碰机密' : undefined}
                            icon={HiOutlineShieldExclamation}
                            tone={result && result.confidentialHits === 0 ? 'success' : (result && result.confidentialHits > 0 ? 'warning' : 'neutral')}
                        />
                        <div className="ml-auto flex items-center gap-1.5">
                            <DsButton variant="secondary" onClick={exportCsv} disabled={!result || result.rowCount === 0}>
                                <HiOutlineArrowDownTray size={13}/> CSV
                            </DsButton>
                            <DsButton variant="secondary" onClick={exportXlsx} disabled={!result || result.rowCount === 0}>
                                <HiOutlineArrowDownTray size={13}/> Excel
                            </DsButton>
                        </div>
                    </div>

                    {/* 结果表（卡片化，紧凑行高） */}
                    <div className="flex-1 min-h-0 bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md flex flex-col overflow-hidden">
                        <div className="px-3 py-2 border-b border-ds-border-subtle flex items-center flex-shrink-0">
                            <span className="text-ds-small font-semibold text-ds-text-primary">查询结果</span>
                            {result && (
                                <span className="text-ds-nano text-ds-text-muted ml-2">
                                    {result.columns.length} 列 · {formatNumber(result.rowCount)} 行
                                </span>
                            )}
                            {result?.truncated && (
                                <span className="text-ds-nano text-ds-warning ml-2">已截断，仅展示前 1000 行</span>
                            )}
                            <span className="ml-auto text-ds-nano text-ds-text-muted">只读 SQL · 上限 1000 行</span>
                        </div>
                        {renderSecurityBanner()}
                        <div className="flex-1 min-h-0 overflow-auto">
                            {result ? (
                                <Table
                                    className="prototype-table"
                                    columns={tableColumns}
                                    dataSource={tableData}
                                    rowKey={(_, idx) => idx ?? 0}
                                    pagination={false}
                                    size="small"
                                    scroll={{x: 'max-content'}}
                                    locale={{emptyText: <DsTableEmpty description="查询结果为空"/>}}
                                />
                            ) : errorMsg ? (
                                <div className="h-full flex flex-col items-center justify-center gap-3 py-6">
                                    <div className="w-12 h-12 rounded-full bg-ds-danger/10 flex items-center justify-center">
                                        {(() => {
                                            const cls = classifyError(errorMsg);
                                            switch (cls.icon) {
                                                case 'blocked': return <HiOutlineShieldExclamation size={24} className="text-ds-danger"/>;
                                                case 'syntax': return <HiOutlineXCircle size={24} className="text-ds-danger"/>;
                                                case 'timeout': return <HiOutlineClock size={24} className="text-ds-danger"/>;
                                                case 'sensitive': return <HiOutlineShieldExclamation size={24} className="text-ds-danger"/>;
                                                default: return <HiOutlineXCircle size={24} className="text-ds-danger"/>;
                                            }
                                        })()}
                                    </div>
                                    <div className="text-center px-6">
                                        <div className="text-ds-small font-semibold text-ds-text-primary">
                                            {classifyError(errorMsg).title}
                                        </div>
                                        <div className="mt-1 text-ds-tiny text-ds-text-muted break-all leading-relaxed max-w-[560px]">
                                            {errorMsg}
                                        </div>
                                    </div>
                                    <DsButton variant="secondary" onClick={() => setErrorMsg(null)}>
                                        关闭
                                    </DsButton>
                                </div>
                            ) : (
                                <div className="h-full flex items-center justify-center text-ds-small text-ds-text-muted">
                                    {running ? '查询执行中…' : '运行 SQL 后结果展示于此'}
                                </div>
                            )}
                        </div>
                    </div>
                </div>
            </div>

            {/* 查询历史 Drawer */}
            <Drawer
                open={historyOpen}
                title="查询历史"
                width="max-w-[560px]"
                onClose={() => setHistoryOpen(false)}
                extra={
                    <DsIconButton
                        tone="default"
                        onClick={() => setClearOpen(true)}
                        aria-label="清空历史"
                        disabled={historyList.length === 0}
                    >
                        <HiOutlineTrash size={18}/>
                    </DsIconButton>
                }
                footer={
                    <div className="w-full">
                        <Pagination
                            page={historyPage}
                            pageSize={historyPageSize}
                            total={historyTotal}
                            onChange={(p, ps) => {
                                setHistoryPage(p);
                                setHistoryPageSize(ps);
                            }}
                        />
                    </div>
                }
            >
                <div className="flex flex-col gap-2">
                    {historyList.length === 0 ? (
                        <div className="text-ds-tiny text-ds-text-muted text-center py-10">
                            {historyLoading ? '加载中…' : '暂无查询历史'}
                        </div>
                    ) : (
                        historyList.map(item => (
                            <button
                                key={item.id}
                                onClick={() => fillFromHistory(item)}
                                className="text-left px-3 py-2 bg-ds-bg-hover/50 hover:bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm transition-colors"
                            >
                                <div className="flex items-center gap-2 mb-1">
                                    <span className="text-ds-nano text-ds-text-secondary px-1.5 py-0.5 bg-ds-bg-hover rounded-ds-xs">
                                        {dsNameOf(String(item.datasourceId))}
                                    </span>
                                    {item.errorMessage ? (
                                        <>
                                            <span className="text-ds-nano text-ds-danger px-1.5 py-0.5 bg-ds-danger/10 rounded-ds-xs font-medium">
                                                失败
                                            </span>
                                            <span className="text-ds-nano text-ds-danger truncate max-w-[200px]" title={item.errorMessage}>
                                                {item.errorMessage}
                                            </span>
                                        </>
                                    ) : (
                                        <span className="text-ds-nano text-ds-text-muted tabular-nums">
                                            {formatDuration(item.durationMs)} · {formatNumber(item.rowCount)} 行
                                        </span>
                                    )}
                                    <span className="ml-auto shrink-0 text-ds-nano text-ds-text-muted">
                                        {formatDateTime(item.createdAt)}
                                    </span>
                                </div>
                                <div className="text-ds-tiny text-ds-text-primary leading-snug break-all line-clamp-2 font-mono">
                                    {item.sqlText}
                                </div>
                            </button>
                        ))
                    )}
                </div>
            </Drawer>

            {/* 清空历史确认 */}
            <ConfirmDialog
                open={clearOpen}
                title="清空查询历史"
                message="确定清空当前账号的全部查询历史吗？此操作不可恢复。"
                confirmLabel="清空"
                danger
                loading={clearing}
                onConfirm={handleClearHistory}
                onCancel={() => setClearOpen(false)}
            />
        </div>
    );
}
