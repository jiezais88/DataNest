import {useEffect, useState} from 'react';
import type {
    DataSource,
    DataSourceCreateRequest,
    DataSourceType,
    DataSourceUpdateRequest
} from '../../../types/datasource';
import {testConnection} from '../../../api/datasource';
import {HiOutlineEye, HiOutlineEyeSlash, HiOutlineXMark} from 'react-icons/hi2';

type FormData = {
    name: string;
    type: DataSourceType | '';
    host: string;
    port: number | '';
    databaseName: string;
    schemaName: string;
    username: string;
    password: string;
    passwordChanged: boolean;
    description: string;
};

const TYPE_OPTIONS: { value: DataSourceType; label: string }[] = [
    {value: 'MYSQL', label: 'MySQL'},
    {value: 'POSTGRESQL', label: 'PostgreSQL'},
    {value: 'DORIS', label: 'Doris'},
];

const DEFAULT_PORTS: Record<DataSourceType, number> = {
    MYSQL: 3306,
    POSTGRESQL: 5432,
    DORIS: 9030,
};

const EMPTY_FORM: FormData = {
    name: '',
    type: '',
    host: '',
    port: '',
    databaseName: '',
    schemaName: '',
    username: '',
    password: '',
    passwordChanged: false,
    description: '',
};

interface DataSourceDrawerProps {
    open: boolean;
    editItem: DataSource | null;
    onClose: () => void;
    onSubmit: (data: DataSourceCreateRequest | DataSourceUpdateRequest) => Promise<{
        code: number;
        message?: string
    } | undefined>;
}

export default function DataSourceDrawer({open, editItem, onClose, onSubmit}: DataSourceDrawerProps) {
    const [form, setForm] = useState<FormData>(EMPTY_FORM);
    const [showPassword, setShowPassword] = useState(false);
    const [testing, setTesting] = useState(false);
    const [testResult, setTestResult] = useState<{ success: boolean; message: string } | null>(null);
    const [submitting, setSubmitting] = useState(false);
    const [errors, setErrors] = useState<Partial<Record<keyof FormData, string>>>({});

    const isEdit = !!editItem;

    useEffect(() => {
        if (open) {
            if (editItem) {
                setForm({
                    name: editItem.name,
                    type: editItem.type,
                    host: editItem.host,
                    port: editItem.port,
                    databaseName: editItem.databaseName,
                    schemaName: editItem.schemaName || '',
                    username: editItem.username,
                    password: '',
                    passwordChanged: false,
                    description: editItem.description || '',
                });
            } else {
                setForm(EMPTY_FORM);
            }
            setTestResult(null);
            setErrors({});
            setShowPassword(false);
        }
    }, [open, editItem]);

    const updateField = <K extends keyof FormData>(field: K, value: FormData[K]) => {
        setForm((prev) => {
            const next = {...prev, [field]: value};
            if (field === 'type' && value && !isEdit) {
                next.port = DEFAULT_PORTS[value as DataSourceType];
            }
            return next;
        });
        if (errors[field]) {
            setErrors((prev) => ({...prev, [field]: undefined}));
        }
    };

    const validate = (): boolean => {
        const nextErrors: Partial<Record<keyof FormData, string>> = {};
        if (!form.name.trim()) nextErrors.name = '请输入数据源名称';
        if (!form.type) nextErrors.type = '请选择数据源类型';
        if (!form.host.trim()) nextErrors.host = '请输入主机地址';
        if (form.port === '' || Number(form.port) <= 0 || Number(form.port) > 65535) nextErrors.port = '请输入有效端口';
        if (!form.databaseName.trim()) nextErrors.databaseName = '请输入数据库名';
        if (form.type === 'POSTGRESQL' && !form.schemaName.trim()) nextErrors.schemaName = 'PostgreSQL 必须填写 Schema';
        if (!form.username.trim()) nextErrors.username = '请输入用户名';
        if (!isEdit && !form.password) nextErrors.password = '请输入密码';
        if (isEdit && form.passwordChanged && !form.password) nextErrors.password = '请输入新密码';
        setErrors(nextErrors);
        return Object.keys(nextErrors).length === 0;
    };

    const handleTest = async () => {
        if (!validate()) return;
        setTesting(true);
        setTestResult(null);
        const result = await testConnection({
            type: form.type as DataSourceType,
            host: form.host.trim(),
            port: Number(form.port),
            databaseName: form.databaseName.trim(),
            schemaName: form.schemaName.trim() || undefined,
            username: form.username.trim(),
            password: form.password,
        });
        setTesting(false);
        if (result && result.code === 200) {
            setTestResult({success: result.data.success, message: result.data.message});
        } else {
            setTestResult({success: false, message: result?.message || '测试请求失败'});
        }
    };

    const handleSubmit = async () => {
        if (!validate()) return;
        setSubmitting(true);
        const payload = isEdit
            ? {
                type: form.type as DataSourceType,
                host: form.host.trim(),
                port: Number(form.port),
                databaseName: form.databaseName.trim(),
                schemaName: form.schemaName.trim() || undefined,
                username: form.username.trim(),
                password: form.password,
                passwordChanged: form.passwordChanged,
                description: form.description.trim() || undefined,
            }
            : {
                name: form.name.trim(),
                type: form.type as DataSourceType,
                host: form.host.trim(),
                port: Number(form.port),
                databaseName: form.databaseName.trim(),
                schemaName: form.schemaName.trim() || undefined,
                username: form.username.trim(),
                password: form.password,
                description: form.description.trim() || undefined,
            };
        const result = await onSubmit(payload as DataSourceCreateRequest | DataSourceUpdateRequest);
        setSubmitting(false);
        if (result && result.code === 200) {
            onClose();
        }
    };

    if (!open) return null;

    return (
        <div className="fixed inset-0 z-50 flex justify-end">
            <div className="absolute inset-0 bg-black/30" onClick={onClose}/>
            <div className="relative w-full max-w-[560px] h-full bg-ds-bg-surface shadow-ds-lg flex flex-col">
                <div
                    className="flex items-center justify-between px-ds-6 py-ds-4 border-b border-ds-border-subtle flex-shrink-0">
                    <h2 className="text-ds-title text-ds-text-primary font-bold">
                        {isEdit ? '编辑数据源' : '新增数据源'}
                    </h2>
                    <button
                        onClick={onClose}
                        className="p-1.5 text-ds-text-muted hover:text-ds-text-primary hover:bg-ds-bg-hover rounded transition-colors"
                        aria-label="关闭"
                    >
                        <HiOutlineXMark size={20}/>
                    </button>
                </div>

                <div className="flex-1 overflow-auto p-ds-6">
                    {testResult && (
                        <div className={`mb-ds-4 px-ds-4 py-ds-3 rounded-ds-sm text-ds-small ${
                            testResult.success
                                ? 'bg-ds-success-light text-ds-success'
                                : 'bg-ds-danger-light text-ds-danger'
                        }`}>
                            {testResult.success ? '连接成功：' : '连接失败：'}{testResult.message}
                        </div>
                    )}

                    <div className="space-y-ds-4">
                        <div>
                            <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                数据源名称 <span className="text-ds-danger">*</span>
                            </label>
                            <input
                                data-testid="datasource-name-input"
                                value={form.name}
                                onChange={(e) => updateField('name', e.target.value)}
                                disabled={isEdit}
                                className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary disabled:bg-ds-bg-disabled disabled:text-ds-text-muted focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                                placeholder="例如：订单业务库"
                            />
                            {errors.name && <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.name}</p>}
                        </div>

                        <div className="grid grid-cols-2 gap-ds-4">
                            <div>
                                <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                    数据源类型 <span className="text-ds-danger">*</span>
                                </label>
                                <select
                                    data-testid="datasource-type-select"
                                    value={form.type}
                                    onChange={(e) => updateField('type', e.target.value as DataSourceType | '')}
                                    className="w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                                >
                                    <option value="">请选择</option>
                                    {TYPE_OPTIONS.map((o) => (
                                        <option key={o.value} value={o.value}>{o.label}</option>
                                    ))}
                                </select>
                                {errors.type && <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.type}</p>}
                            </div>
                            <div>
                                <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                    端口 <span className="text-ds-danger">*</span>
                                </label>
                                <input
                                    data-testid="datasource-port-input"
                                    type="number"
                                    value={form.port}
                                    onChange={(e) => updateField('port', e.target.value === '' ? '' : Number(e.target.value))}
                                    className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                                    placeholder="3306"
                                />
                                {errors.port && <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.port}</p>}
                            </div>
                        </div>

                        <div>
                            <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                主机地址 <span className="text-ds-danger">*</span>
                            </label>
                            <input
                                data-testid="datasource-host-input"
                                value={form.host}
                                onChange={(e) => updateField('host', e.target.value)}
                                className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                                placeholder="例如：192.168.1.10 或 mysql.example.com"
                            />
                            {errors.host && <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.host}</p>}
                        </div>

                        <div className="grid grid-cols-2 gap-ds-4">
                            <div>
                                <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                    数据库名 <span className="text-ds-danger">*</span>
                                </label>
                                <input
                                    data-testid="datasource-database-input"
                                    value={form.databaseName}
                                    onChange={(e) => updateField('databaseName', e.target.value)}
                                    className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                                    placeholder="例如：orders"
                                />
                                {errors.databaseName &&
                                    <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.databaseName}</p>}
                            </div>
                            <div>
                                <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                    Schema {form.type === 'POSTGRESQL' && <span className="text-ds-danger">*</span>}
                                </label>
                                <input
                                    data-testid="datasource-schema-input"
                                    value={form.schemaName}
                                    onChange={(e) => updateField('schemaName', e.target.value)}
                                    className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                                    placeholder="例如：public"
                                />
                                {errors.schemaName &&
                                    <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.schemaName}</p>}
                            </div>
                        </div>

                        <div className="grid grid-cols-2 gap-ds-4">
                            <div>
                                <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                    用户名 <span className="text-ds-danger">*</span>
                                </label>
                                <input
                                    data-testid="datasource-username-input"
                                    value={form.username}
                                    onChange={(e) => updateField('username', e.target.value)}
                                    className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                                    placeholder="例如：readonly"
                                />
                                {errors.username &&
                                    <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.username}</p>}
                            </div>
                            <div>
                                <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                    密码 <span className="text-ds-danger">*</span>
                                </label>
                                <div className="relative">
                                    <input
                                        data-testid="datasource-password-input"
                                        type={showPassword ? 'text' : 'password'}
                                        value={form.password}
                                        onChange={(e) => {
                                            updateField('password', e.target.value);
                                            if (isEdit && !form.passwordChanged) {
                                                updateField('passwordChanged', true);
                                            }
                                        }}
                                        className="w-full pl-ds-3 pr-9 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                                        placeholder={isEdit && !form.passwordChanged ? '********' : '请输入密码'}
                                    />
                                    <button
                                        type="button"
                                        onClick={() => setShowPassword((v) => !v)}
                                        className="absolute right-2 top-1/2 -translate-y-1/2 text-ds-text-muted hover:text-ds-text-secondary"
                                        aria-label={showPassword ? '隐藏密码' : '显示密码'}
                                    >
                                        {showPassword ? <HiOutlineEyeSlash size={18}/> : <HiOutlineEye size={18}/>}
                                    </button>
                                </div>
                                {errors.password &&
                                    <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.password}</p>}
                            </div>
                        </div>

                        <div>
                            <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                描述
                            </label>
                            <textarea
                                data-testid="datasource-description-input"
                                value={form.description}
                                onChange={(e) => updateField('description', e.target.value)}
                                rows={3}
                                className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors resize-none"
                                placeholder="可选：填写数据源用途或备注"
                            />
                        </div>
                    </div>
                </div>

                <div
                    className="flex items-center justify-end gap-ds-3 px-ds-6 py-ds-4 border-t border-ds-border-subtle flex-shrink-0">
                    <button
                        data-testid="datasource-drawer-test-btn"
                        onClick={handleTest}
                        disabled={testing}
                        className="px-ds-4 py-ds-2 bg-white border border-ds-border-subtle hover:border-ds-accent hover:text-ds-accent text-ds-text-secondary text-ds-small font-semibold rounded-ds-sm transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                    >
                        {testing ? '测试中...' : '测试连接'}
                    </button>
                    <button
                        data-testid="datasource-drawer-cancel-btn"
                        onClick={onClose}
                        className="px-ds-4 py-ds-2 bg-white border border-ds-border-subtle hover:border-ds-border-strong text-ds-text-secondary text-ds-small font-semibold rounded-ds-sm transition-colors"
                    >
                        取消
                    </button>
                    <button
                        data-testid="datasource-drawer-save-btn"
                        onClick={handleSubmit}
                        disabled={submitting}
                        className="px-ds-4 py-ds-2 bg-ds-accent hover:bg-ds-accent-hover disabled:opacity-60 disabled:cursor-not-allowed text-white text-ds-small font-semibold rounded-ds-sm transition-colors"
                    >
                        {submitting ? '保存中...' : '保存'}
                    </button>
                </div>
            </div>
        </div>
    );
}
