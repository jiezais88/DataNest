// Sprint 4 手动触发参数覆盖弹窗（PRD §6.4.4）
// 点击「执行」时若 DAG 存在参数则弹出：控件按参数类型驱动（DATE→日期选择 / NUMBER→数字 /
// BOOLEAN→radio / STRING→文本），每项带默认值 hint；必填参数未填写禁止执行。
import {useEffect, useState} from 'react';
import DsButton from '@/components/DsButton';
import DsModal from '@/components/DsModal';
import type {DagParameter} from '@/pages/engineering/dags/types';

interface TriggerParamsModalProps {
    open: boolean;
    params: DagParameter[];
    executing?: boolean;
    onCancel: () => void;
    /** overrides 的值为 string / number / boolean（后端 resolveParams 直接做 ${} 替换） */
    onExecute: (overrides: Record<string, unknown>) => void;
}

export default function TriggerParamsModal({
                                               open,
                                               params,
                                               executing = false,
                                               onCancel,
                                               onExecute,
                                           }: TriggerParamsModalProps) {
    // 表单值统一用 string 存储（BOOLEAN 用 'true'/'false'），提交时按类型还原
    const [values, setValues] = useState<Record<string, string>>({});
    const [errors, setErrors] = useState<Record<string, string>>({});

    // 打开时按默认值初始化
    useEffect(() => {
        if (!open) return;
        const init: Record<string, string> = {};
        params.forEach(p => {
            init[p.paramName] = p.defaultValue ?? '';
        });
        setValues(init);
        setErrors({});
    }, [open, params]);

    const setValue = (name: string, value: string) => {
        setValues(prev => ({...prev, [name]: value}));
        if (errors[name]) {
            setErrors(prev => ({...prev, [name]: undefined as unknown as string}));
        }
    };

    const handleExecute = () => {
        const nextErrors: Record<string, string> = {};
        const overrides: Record<string, unknown> = {};
        for (const p of params) {
            const raw = (values[p.paramName] ?? '').trim();
            if (p.required && !raw) {
                nextErrors[p.paramName] = '必填参数';
                continue;
            }
            if (!raw) continue;
            if (p.paramType === 'NUMBER') {
                if (Number.isNaN(Number(raw))) {
                    nextErrors[p.paramName] = '必须是数字';
                    continue;
                }
                overrides[p.paramName] = Number(raw);
            } else if (p.paramType === 'BOOLEAN') {
                overrides[p.paramName] = raw === 'true';
            } else if (p.paramType === 'DATE') {
                if (!/^\d{4}-\d{2}-\d{2}$/.test(raw)) {
                    nextErrors[p.paramName] = '必须是 yyyy-MM-dd 格式';
                    continue;
                }
                overrides[p.paramName] = raw;
            } else {
                overrides[p.paramName] = raw;
            }
        }
        setErrors(nextErrors);
        if (Object.keys(nextErrors).length > 0) return;
        onExecute(overrides);
    };

    return (
        <DsModal
            open={open}
            onClose={onCancel}
            title="触发执行"
            width="w-[520px] max-w-[96vw]"
            maskClosable={!executing}
            closable={!executing}
            footer={
                <>
                    <DsButton variant="secondary" onClick={onCancel} disabled={executing}>
                        取消
                    </DsButton>
                    <DsButton onClick={handleExecute} disabled={executing}>
                        {executing ? '执行中...' : '执行'}
                    </DsButton>
                </>
            }
        >
            <div className="space-y-ds-4">
                {params.map(p => (
                    <div key={p.paramName}>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                            {p.description || p.paramName}
                            {p.required && <span className="text-ds-danger"> *</span>}
                        </label>
                        {p.paramType === 'DATE' ? (
                            <input
                                type="date"
                                value={values[p.paramName] ?? ''}
                                onChange={e => setValue(p.paramName, e.target.value)}
                                className="w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                            />
                        ) : p.paramType === 'BOOLEAN' ? (
                            <div className="flex items-center gap-ds-4">
                                {['true', 'false'].map(opt => (
                                    <label key={opt}
                                           className="flex items-center gap-ds-1 text-ds-body text-ds-text-primary">
                                        <input
                                            type="radio"
                                            name={`trigger-param-${p.paramName}`}
                                            checked={(values[p.paramName] ?? '') === opt}
                                            onChange={() => setValue(p.paramName, opt)}
                                        />
                                        {opt}
                                    </label>
                                ))}
                            </div>
                        ) : (
                            <input
                                type={p.paramType === 'NUMBER' ? 'number' : 'text'}
                                value={values[p.paramName] ?? ''}
                                onChange={e => setValue(p.paramName, e.target.value)}
                                placeholder={p.paramName}
                                className="w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                            />
                        )}
                        <p className="mt-ds-1 text-ds-nano text-ds-text-muted">
                            {p.paramName} · 默认值：{p.defaultValue || '—'}
                        </p>
                        {errors[p.paramName] && (
                            <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors[p.paramName]}</p>
                        )}
                    </div>
                ))}
            </div>
        </DsModal>
    );
}
