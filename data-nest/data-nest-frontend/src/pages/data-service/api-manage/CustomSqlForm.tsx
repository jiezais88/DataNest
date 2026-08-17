// Sprint 13 F1：自定义 SQL 定义表单（创建向导第 2 步 / 编辑页共用）。
// 数据源单选（复用 DsFilterSelect）+ Monaco SQL 编辑器（复用 sql-console 的 monacoSetup）
// + 校验按钮（词法只读预检 + :参数识别 + 涉及表提取，权威校验由后端保存时兜底）
// + 参数表（类型/必填/默认值可修正、可删除）+ 涉及表 chips + 试跑预览（复用 SQL 终端执行接口）。
import {useCallback, useMemo, useRef, useState} from 'react';
import {Table} from 'antd';
import {
    HiOutlineCheckCircle,
    HiOutlineExclamationTriangle,
    HiOutlinePlay,
    HiOutlineShieldCheck,
    HiOutlineTrash,
    HiOutlineXCircle,
} from 'react-icons/hi2';
import * as monaco from 'monaco-editor/editor/editor.api';
import '@/lib/monacoSetup';
import Editor, {type OnMount} from '@monaco-editor/react';
import {executeSql} from '@/api/data-service';
import {CUSTOM_SQL_PARAM_TYPE_LABEL} from '@/types/data-service';
import type {CustomSqlParamDef, CustomSqlParamType, SqlDatasource} from '@/types/data-service';
import DsButton from '@/components/DsButton';
import DsFilterSelect from '@/components/DsFilterSelect';
import DsSelect from '@/components/DsSelect';
import DsTableEmpty from '@/components/DsTableEmpty';
import {getErrorMessage} from '@/utils/error';
import {formatNumber} from '@/utils/format';
import {
    buildPreviewSql,
    clientCheckReadOnly,
    extractInvolvedTables,
    inferSqlParamType,
    scanSqlParams,
} from './customSql';
import type {CustomSqlState} from './customSql';

interface CustomSqlFormProps {
    value: CustomSqlState;
    onChange: (next: CustomSqlState) => void;
    /** 可访问数据源下拉（复用 SQL 终端 listSqlDatasources） */
    datasources: SqlDatasource[];
    /** 只读模式（编辑页）：数据源创建后不可更换，下拉仅展示 */
    readOnly?: boolean;
}

/** 参数类型下拉选项 */
const TYPE_OPTIONS = (Object.keys(CUSTOM_SQL_PARAM_TYPE_LABEL) as CustomSqlParamType[])
    .map((t) => ({value: t, label: CUSTOM_SQL_PARAM_TYPE_LABEL[t]}));

/** 同步参数表：SQL 里 :参数 与 sqlParams 定义一一对应（新参数保留类型推断，已消失参数剔除） */
function syncParamsFromSql(sql: string, prev: CustomSqlParamDef[]): CustomSqlParamDef[] {
    const placeholders = scanSqlParams(sql);
    const byName = new Map(prev.map((p) => [p.name, p]));
    return placeholders.map((name) => {
        const old = byName.get(name);
        if (old) return old;
        return {name, type: 'STRING' as CustomSqlParamType, required: true, defaultValue: null};
    });
}

export default function CustomSqlForm({value, onChange, datasources, readOnly}: CustomSqlFormProps) {
    const [previewing, setPreviewing] = useState(false);
    const [preview, setPreview] = useState<{
        columns: string[];
        rows: Record<string, unknown>[];
        truncated: boolean;
        durationMs: number;
        rowCount: number;
    } | null>(null);
    const [previewError, setPreviewError] = useState<string | null>(null);
    const editorRef = useRef<Parameters<OnMount>[0] | null>(null);

    const dsDisplayName = (ds: SqlDatasource) => (ds.builtin ? 'Doris 数仓' : `${ds.name}（${ds.type}）`);

    /** 校验 SQL：只读预检 + 参数识别 + 涉及表提取；失败不阻断编辑但禁止进入下一步 */
    const handleValidate = useCallback(() => {
        const sql = value.sqlText;
        const err = clientCheckReadOnly(sql);
        if (err) {
            onChange({
                ...value,
                validated: false,
                validateMessage: err,
                dirty: false,
                involvedTables: extractInvolvedTables(sql),
            });
            return;
        }
        const params = syncParamsFromSql(sql, value.sqlParams);
        const tables = extractInvolvedTables(sql);
        const msg = `校验通过：识别参数 ${params.length} 个 · 涉及表 ${tables.length} 张（保存时由后端做权威校验与权限闸门）`;
        onChange({...value, sqlParams: params, involvedTables: tables, validated: true, validateMessage: msg, dirty: false});
    }, [value, onChange]);

    const validateRef = useRef(handleValidate);
    validateRef.current = handleValidate;

    const handleEditorMount: OnMount = useCallback((editor) => {
        editorRef.current = editor;
        editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => validateRef.current());
    }, []);

    /** 试跑预览：复用 SQL 终端执行接口（词法替换 :param 为默认值/示例值，返回前 100 条） */
    const handlePreview = useCallback(async () => {
        const sql = value.sqlText.trim();
        if (!sql) {
            onChange({...value, validateMessage: '请输入 SQL 后再试跑预览'});
            return;
        }
        if (!value.datasourceId) {
            onChange({...value, validateMessage: '请先选择数据源'});
            return;
        }
        const err = clientCheckReadOnly(sql);
        if (err) {
            onChange({...value, validated: false, validateMessage: err});
            return;
        }
        // 本地同步参数（避免依赖 state 异步更新），未定义的新参数在预览中自动取示例值
        const params = syncParamsFromSql(sql, value.sqlParams);
        setPreviewing(true);
        setPreview(null);
        setPreviewError(null);
        try {
            const res = await executeSql({
                datasourceId: value.datasourceId,
                sql: buildPreviewSql(sql, params),
                timeoutSeconds: 10,
            });
            setPreview(res.data);
        } catch (e) {
            setPreviewError(getErrorMessage(e, '预览执行失败'));
        } finally {
            setPreviewing(false);
        }
    }, [value, onChange]);

    const updateParam = useCallback((name: string, patch: Partial<CustomSqlParamDef>) => {
        onChange({
            ...value,
            sqlParams: value.sqlParams.map((p) => (p.name === name ? {...p, ...patch} : p)),
            validated: false,
            dirty: true,
        });
    }, [value, onChange]);

    const removeParam = useCallback((name: string) => {
        onChange({
            ...value,
            sqlParams: value.sqlParams.filter((p) => p.name !== name),
            validated: false,
            dirty: true,
        });
    }, [value, onChange]);

    const previewColumns = useMemo(() => (preview?.columns ?? []).map((c) => ({
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
    })), [preview]);

    return (
        <div className="flex flex-col gap-ds-5">
            {/* 数据源（限定单数据源，仅展示当前用户可访问的数据源） */}
            <div className="max-w-[480px]">
                <label className="block text-ds-small text-ds-text-secondary mb-1">
                    数据源 <span className="text-ds-danger">*</span>
                </label>
                <DsFilterSelect
                    value={value.datasourceId}
                    onChange={(v) => onChange({...value, datasourceId: v, validated: false, dirty: true})}
                    aria-label="数据源"
                    options={datasources.map((d) => ({value: d.id, label: dsDisplayName(d)}))}
                    className="w-full"
                    disabled={readOnly}
                />
                <p className="text-ds-caption text-ds-text-muted mt-1">
                    {readOnly ? '数据源创建后不可更换' : '限定单一数据源内查询（跨源 JOIN 留联邦查询 Sprint 20）'}
                </p>
            </div>

            {/* SQL 编辑器 */}
            <div>
                <label className="block text-ds-small text-ds-text-secondary mb-1">
                    SQL（只读） <span className="text-ds-danger">*</span>
                </label>
                <div className="border border-ds-border-subtle rounded-ds-sm overflow-hidden bg-[#1e1e1e]">
                    <Editor
                        height="220px"
                        defaultLanguage="sql"
                        theme="vs-dark"
                        value={value.sqlText}
                        onChange={(v) => onChange({
                            ...value,
                            sqlText: v ?? '',
                            validated: false,
                            dirty: true,
                            involvedTables: [],
                        })}
                        onMount={handleEditorMount}
                        options={{
                            padding: {top: 8, bottom: 8},
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
                <div className="flex items-center gap-ds-2 mt-ds-2 flex-wrap">
                    <DsButton variant="secondary" onClick={handleValidate} disabled={!value.sqlText.trim()}>
                        <HiOutlineShieldCheck size={14}/>
                        校验 SQL
                    </DsButton>
                    <DsButton variant="secondary" onClick={handlePreview} loading={previewing} disabled={!value.sqlText.trim()}>
                        <HiOutlinePlay size={14}/>
                        试跑预览
                    </DsButton>
                    <span className="text-ds-caption text-ds-text-muted">
                        SQL 中的 <span className="font-mono text-ds-accent">:参数名</span> 自动识别为 API 参数；Ctrl+Enter 校验；预览返回前 100 条
                    </span>
                </div>

                {/* 校验结果（行内展示，不阻断编辑） */}
                {value.validateMessage && (
                    value.validated ? (
                        <div className="flex items-start gap-ds-2 mt-ds-2 px-ds-3 py-ds-2 rounded-ds-sm bg-ds-success-light text-ds-success text-ds-small">
                            <HiOutlineCheckCircle size={15} className="mt-0.5 flex-shrink-0"/>
                            <span>{value.validateMessage}</span>
                        </div>
                    ) : (
                        <div className="flex items-start gap-ds-2 mt-ds-2 px-ds-3 py-ds-2 rounded-ds-sm bg-ds-danger-light text-ds-danger text-ds-small">
                            <HiOutlineExclamationTriangle size={15} className="mt-0.5 flex-shrink-0"/>
                            <span>{value.validateMessage}</span>
                        </div>
                    )
                )}
            </div>

            {/* 参数表 */}
            <div>
                <div className="flex items-center justify-between mb-1">
                    <label className="block text-ds-small text-ds-text-secondary">
                        参数 <span className="text-ds-caption text-ds-text-muted font-normal">（由 SQL 中的 :参数名 自动识别，可修正类型/必填/默认值）</span>
                    </label>
                </div>
                {value.sqlParams.length === 0 ? (
                    <p className="text-ds-small text-ds-text-muted border border-dashed border-ds-border-strong rounded-ds-sm px-ds-3 py-ds-3">
                        暂无参数。SQL 中使用 <span className="font-mono">:参数名</span>（如 <span className="font-mono">:startDate</span>）后点击「校验 SQL」自动识别。
                    </p>
                ) : (
                    <div className="border border-ds-border-subtle rounded-ds-sm overflow-hidden">
                        <div className="grid grid-cols-[1fr_180px_90px_1fr_36px] items-center gap-ds-2 px-ds-3 py-ds-2 bg-ds-bg-hover text-ds-caption font-semibold text-ds-text-muted">
                            <span>参数名</span>
                            <span>类型</span>
                            <span>必填</span>
                            <span>默认值</span>
                            <span/>
                        </div>
                        {value.sqlParams.map((p) => (
                            <div key={p.name}
                                 className="grid grid-cols-[1fr_180px_90px_1fr_36px] items-center gap-ds-2 px-ds-3 py-ds-2 border-t border-ds-border-subtle">
                                <span className="inline-flex items-center justify-self-start px-ds-2 py-0.5 rounded-ds-xs bg-ds-bg-root border border-ds-border-subtle font-mono text-ds-small text-ds-text-primary">
                                    :{p.name}
                                </span>
                                <DsSelect
                                    value={p.type}
                                    onChange={(v) => updateParam(p.name, {type: v as CustomSqlParamType})}
                                    aria-label={`参数 ${p.name} 类型`}
                                    className="py-ds-1 text-ds-small"
                                >
                                    {TYPE_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                                </DsSelect>
                                <label className="flex items-center gap-ds-1.5 text-ds-small text-ds-text-secondary cursor-pointer">
                                    <input
                                        type="checkbox"
                                        checked={p.required}
                                        onChange={(e) => updateParam(p.name, {required: e.target.checked})}
                                        className="accent-ds-accent"
                                    />
                                    必填
                                </label>
                                <input
                                    value={p.defaultValue ?? ''}
                                    onChange={(e) => {
                                        const dv = e.target.value;
                                        updateParam(p.name, {
                                            defaultValue: dv,
                                            type: dv ? inferSqlParamType(dv) : p.type,
                                        });
                                    }}
                                    placeholder="默认值（选填）"
                                    className="w-full px-ds-2 py-ds-1 border border-ds-border-subtle rounded-ds-sm text-ds-small font-mono focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent"
                                />
                                <button
                                    type="button"
                                    onClick={() => removeParam(p.name)}
                                    title="删除参数"
                                    className="text-ds-text-muted hover:text-ds-danger transition-colors"
                                >
                                    <HiOutlineTrash size={14}/>
                                </button>
                            </div>
                        ))}
                    </div>
                )}
            </div>

            {/* 涉及表 */}
            <div>
                <label className="block text-ds-small text-ds-text-secondary mb-1">
                    涉及表 <span className="text-ds-caption text-ds-text-muted font-normal">（将用于权限校验与血缘）</span>
                </label>
                {value.involvedTables.length === 0 ? (
                    <p className="text-ds-small text-ds-text-muted">校验 SQL 后展示涉及的表清单</p>
                ) : (
                    <div className="flex flex-wrap items-center gap-ds-2">
                        {value.involvedTables.map((t) => (
                            <span key={t}
                                  className="px-ds-2.5 py-0.5 rounded-ds-xs bg-ds-bg-root border border-ds-border-subtle font-mono text-ds-small text-ds-text-secondary">
                                {t}
                            </span>
                        ))}
                        <span className="text-ds-caption text-ds-text-muted">
                            共 {value.involvedTables.length} 张 · 任一为机密/未特批内部/无权限表将整体拒绝（fail-closed）
                        </span>
                    </div>
                )}
            </div>

            {/* 试跑预览结果 */}
            {preview && (
                <div>
                    <div className="flex items-center justify-between mb-1">
                        <label className="block text-ds-small text-ds-text-secondary">
                            预览结果 <span className="text-ds-caption text-ds-text-muted font-normal">
                            {preview.columns.length} 列 · {formatNumber(preview.rowCount)} 行 · 用时 {preview.durationMs}ms
                            {preview.truncated ? '（已截断，仅展示前 100 条）' : ''}
                        </span>
                        </label>
                    </div>
                    <div className="border border-ds-border-subtle rounded-ds-sm overflow-hidden max-h-[280px] overflow-y-auto">
                        <Table
                            className="prototype-table"
                            columns={previewColumns}
                            dataSource={preview.rows}
                            rowKey={(_, idx) => idx ?? 0}
                            pagination={false}
                            size="small"
                            scroll={{x: 'max-content'}}
                            locale={{emptyText: <DsTableEmpty description="预览结果为空"/>}}
                        />
                    </div>
                </div>
            )}
            {previewError && (
                <div className="flex items-start gap-ds-2 px-ds-3 py-ds-2 rounded-ds-sm bg-ds-danger-light text-ds-danger text-ds-small">
                    <HiOutlineXCircle size={15} className="mt-0.5 flex-shrink-0"/>
                    <span>{previewError}</span>
                </div>
            )}
        </div>
    );
}
