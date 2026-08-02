// Sprint 4 Python Task Editor Modal
// 结构与 SqlEditorModal 对齐：900x620 dark Monaco + 工具栏 + 结果区
// 差异点：
//   - language=python；附加超时/内存限制配置；内置 helper 固定说明区
//   - 「运行测试」调 POST /dev/dags/{dagId}/nodes/{nodeId}/python/test（隔离进程执行，
//     不注册元数据、不影响 DAG 执行状态）；DAG 未保存（无 dagId）时禁用
// onTested: 运行测试结束后把整体结果（success=PASSED，否则 FAILED）回传父组件，
// 父组件写回节点 data.lastTestStatus 驱动画布状态点（与 SQL 节点同一机制）。

import {useEffect, useRef, useState} from 'react';
import {Spin} from 'antd';
import DsButton from '../../../../components/DsButton';
import DsModal from '../../../../components/DsModal';
import '../../../../lib/monacoSetup';
import Editor, {type OnMount} from '@monaco-editor/react';
import {testPythonNode, testPythonScript} from '../api';
import type {PythonExecuteResult} from '../types';
import {formatDuration} from '../../../../utils/format';
import {getErrorMessage} from '../../../../utils/error';

interface PythonEditorModalProps {
    open: boolean;
    onClose: () => void;
    /** 运行测试接口路径需要 dagId；新建未保存的 DAG 没有 id，此时禁用运行测试 */
    dagId?: string | number;
    /** 画布节点 id（nodeId），运行测试接口路径需要；未保存节点可传 draft */
    nodeId?: string;
    /** DAG 有未保存变更；Python 运行测试只依赖脚本内容，不依赖节点是否已持久化，因此不影响运行测试 */
    hasUnsavedChanges?: boolean;
    initialScript?: string;
    initialNodeName?: string;
    initialTimeoutMinutes?: number;
    initialMemoryLimitMb?: number;
    onSave: (script: string, nodeName: string, timeoutMinutes?: number, memoryLimitMb?: number) => void;
    /** 运行测试结束回调：success=PASSED，否则 FAILED（含请求失败/超时） */
    onTested?: (status: 'PASSED' | 'FAILED') => void;
    title?: string;
    /** Sprint 4 权限：true 时禁用「运行测试」「保存」按钮（治理员/分析师只读） */
    readOnly?: boolean;
}

const DEFAULT_TITLE = '编辑 Python 任务';
const DEFAULT_TIMEOUT_MINUTES = 30;
const DEFAULT_MEMORY_MB = 2048;

// 内置 helper 固定说明（与后端 PythonExecutor 注入的上下文函数保持一致）
const HELPER_DOCS: { signature: string; description: string }[] = [
    {signature: 'read_doris_table(table)', description: '读取 Doris 表，返回 pandas DataFrame'},
    {signature: 'write_doris_table(df, table)', description: '将 DataFrame 写入 Doris 表'},
    {signature: "get_param(name)", description: "获取 DAG 参数值，如 get_param('biz_date')"},
    {signature: 'log(message)', description: '输出日志，进入节点执行日志'},
];

export default function PythonEditorModal({
                                              open,
                                              onClose,
                                              dagId,
                                              nodeId,
                                              hasUnsavedChanges = false,
                                              initialScript = '',
                                              initialNodeName = '',
                                              initialTimeoutMinutes,
                                              initialMemoryLimitMb,
                                              onSave,
                                              onTested,
                                              title,
                                              readOnly = false,
                                          }: PythonEditorModalProps) {
    const [nodeName, setNodeName] = useState(initialNodeName);
    const [script, setScript] = useState(initialScript);
    const [timeoutMinutes, setTimeoutMinutes] = useState<number>(initialTimeoutMinutes ?? DEFAULT_TIMEOUT_MINUTES);
    const [memoryLimitMb, setMemoryLimitMb] = useState<number>(initialMemoryLimitMb ?? DEFAULT_MEMORY_MB);
    const [running, setRunning] = useState(false);
    const [result, setResult] = useState<PythonExecuteResult | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [cursorLine, setCursorLine] = useState(1);
    const [cursorCol, setCursorCol] = useState(1);

    const editorRef = useRef<Parameters<OnMount>[0] | null>(null);

    // 重置：当 open 切换时同步初始值（避免脏数据）
    useEffect(() => {
        if (open) {
            setScript(initialScript);
            setNodeName(initialNodeName);
            setTimeoutMinutes(initialTimeoutMinutes ?? DEFAULT_TIMEOUT_MINUTES);
            setMemoryLimitMb(initialMemoryLimitMb ?? DEFAULT_MEMORY_MB);
            setResult(null);
            setError(null);
        }
    }, [open, initialScript, initialNodeName, initialTimeoutMinutes, initialMemoryLimitMb]);

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
        setResult(null);
        try {
            let resp: PythonExecuteResult;
            if (dagId && nodeId) {
                // DAG 已保存且节点有持久化 id：走带参数解析的接口
                resp = await testPythonNode(dagId, nodeId, script, {}, timeoutMinutes);
            } else {
                // 新建 DAG 或节点未保存：走独立脚本测试接口，不解析 DAG 级参数
                resp = await testPythonScript(script, {}, timeoutMinutes);
            }
            setResult(resp);
            onTested?.(resp.success ? 'PASSED' : 'FAILED');
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
        // 与 SqlEditorModal 一致：直接 setSelection 全量范围 + focus，行为确定
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
        onSave(script, nodeName.trim() || 'Python 节点', timeoutMinutes, memoryLimitMb);
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
                        disabled={readOnly || running || !script.trim()}
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
                        placeholder="如：用户行为解析"
                        className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors duration-ds-fast"
                    />
                </div>

                {/* section title */}
                <div className="text-ds-caption text-ds-text-muted font-bold uppercase tracking-wider">
                    Python 编辑器
                </div>

                {/* toolbar */}
                <div className="flex items-center gap-ds-2">
                    <DsButton
                        variant="primary"
                        onClick={handleRunTest}
                        disabled={readOnly || running || !script.trim()}
                        title={readOnly ? '只读模式：您没有编辑权限' : undefined}
                    >
                        {running ? '运行中...' : '▶ 运行测试'}
                    </DsButton>
                    <DsButton variant="secondary" onClick={handleSelectAll}>
                        全选
                    </DsButton>
                    <DsButton variant="secondary" onClick={handleUndo}>
                        撤销
                    </DsButton>
                    <DsButton variant="secondary" onClick={handleRedo}>
                        重做
                    </DsButton>
                </div>

                {/* monaco editor */}
                <div className="border border-ds-border-subtle rounded-ds-sm overflow-hidden">
                    <Editor
                        height="300px"
                        defaultLanguage="python"
                        theme="vs-dark"
                        value={script}
                        onChange={(v) => setScript(v || '')}
                        onMount={handleEditorMount}
                        options={{
                            fontSize: 13,
                            minimap: {enabled: false},
                            wordWrap: 'on',
                            lineNumbers: 'on',
                            scrollBeyondLastLine: false,
                            automaticLayout: true,
                            tabSize: 4,
                        }}
                    />
                </div>

                {/* status bar */}
                <div className="text-ds-caption text-ds-text-muted font-mono">
                    行: {cursorLine} &nbsp; 列: {cursorCol}
                </div>

                {/* 超时 / 内存限制 */}
                <div className="grid grid-cols-2 gap-ds-4">
                    <div>
                        <label
                            className="block text-ds-caption font-bold uppercase tracking-wider text-ds-text-secondary mb-1">
                            超时时间（分钟）
                        </label>
                        <input
                            type="number"
                            min={1}
                            value={timeoutMinutes}
                            onChange={(e) => setTimeoutMinutes(Number(e.target.value) || DEFAULT_TIMEOUT_MINUTES)}
                            className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors duration-ds-fast"
                        />
                    </div>
                    <div>
                        <label
                            className="block text-ds-caption font-bold uppercase tracking-wider text-ds-text-secondary mb-1">
                            内存限制（MB）
                        </label>
                        <input
                            type="number"
                            min={128}
                            value={memoryLimitMb}
                            onChange={(e) => setMemoryLimitMb(Number(e.target.value) || DEFAULT_MEMORY_MB)}
                            className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors duration-ds-fast"
                        />
                    </div>
                </div>

                {/* 内置 helper 说明 */}
                <div className="bg-ds-bg-root border border-ds-border-subtle rounded-ds-sm p-ds-3">
                    <div className="text-ds-caption text-ds-text-muted font-bold uppercase tracking-wider mb-ds-1">
                        内置 helper（脚本中可直接调用）
                    </div>
                    <div className="space-y-0.5 text-ds-caption text-ds-text-secondary">
                        {HELPER_DOCS.map(h => (
                            <div key={h.signature}>
                                <span className="font-mono text-ds-accent">{h.signature}</span>
                                <span className="text-ds-text-muted"> — {h.description}</span>
                            </div>
                        ))}
                    </div>
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
                        执行中（隔离进程运行脚本，最长 {timeoutMinutes} 分钟）...
                    </div>
                )}

                {/* execution result */}
                {!running && result && (
                    <div className="border border-ds-border-subtle rounded-ds-sm overflow-hidden">
                        {/* 状态栏：成功/失败/超时 + 耗时 + exitCode */}
                        <div
                            className="px-ds-4 py-ds-2 bg-ds-bg-hover text-ds-caption font-semibold flex items-center gap-ds-3">
                            {result.success ? (
                                <span className="text-ds-success">✅ 成功</span>
                            ) : result.timeout ? (
                                <span className="text-ds-danger">⏱ 超时被终止</span>
                            ) : (
                                <span className="text-ds-danger">❌ 失败</span>
                            )}
                            {result.durationMs != null && (
                                <span className="text-ds-text-muted font-normal">
                                    耗时 {formatDuration(result.durationMs)}
                                </span>
                            )}
                            {result.exitCode != null && (
                                <span className="text-ds-text-muted font-normal">
                                    exitCode: {result.exitCode}
                                </span>
                            )}
                            {result.outputTables && result.outputTables.length > 0 && (
                                <span className="text-ds-text-muted font-normal">
                                    输出表：{result.outputTables.join('、')}
                                </span>
                            )}
                        </div>
                        {/* stdout / stderr 深色日志体 */}
                        <div className="bg-[#1e293b] px-ds-4 py-ds-3 max-h-[140px] overflow-auto">
                            <div className="text-ds-caption font-mono text-[#64748b] mb-1">stdout:</div>
                            <pre
                                className="text-ds-caption font-mono text-[#e2e8f0] whitespace-pre-wrap break-all m-0 mb-ds-2">
                                {result.stdout || '—'}
                            </pre>
                            <div className="text-ds-caption font-mono text-[#64748b] mb-1">stderr:</div>
                            <pre
                                className={`text-ds-caption font-mono whitespace-pre-wrap break-all m-0 ${result.stderr ? 'text-[#f87171]' : 'text-[#e2e8f0]'}`}>
                                {result.stderr || '—'}
                            </pre>
                        </div>
                    </div>
                )}
            </div>
        </DsModal>
    );
}
