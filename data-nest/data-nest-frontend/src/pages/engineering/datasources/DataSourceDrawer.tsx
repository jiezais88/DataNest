import {useEffect, useState} from 'react';
import type {DataSource, DataSourceCreateRequest, DataSourceUpdateRequest} from '@/types/datasource';
import {
    DataSourceType,
    DataSourceTypeEnum,
    DB_TYPES_WITHOUT_SCHEMA,
    DEFAULT_PORTS,
    TYPE_OPTIONS
} from '@/constants/datasource';
import {testConnection} from '@/api/datasource';
import {HiOutlineEye, HiOutlineEyeSlash} from 'react-icons/hi2';
import TestResultModal from '@/components/TestResultModal';
import DsButton from '@/components/DsButton';
import Drawer from '@/components/Drawer';

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
    autoCollectOnSave: boolean;
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
    autoCollectOnSave: true,
};

interface DataSourceDrawerProps {
    open: boolean;
    editItem?: DataSource | null;
    mode?: 'create' | 'edit' | 'view';
    onClose: () => void;
    onSubmit?: (data: DataSourceCreateRequest | DataSourceUpdateRequest) => Promise<{
        code: number;
        message?: string
    } | undefined>;
}

export default function DataSourceDrawer({open, editItem, mode, onClose, onSubmit}: DataSourceDrawerProps) {
    const [form, setForm] = useState<FormData>(EMPTY_FORM);
    const [showPassword, setShowPassword] = useState(false);
    const [testing, setTesting] = useState(false);
    const [testModalOpen, setTestModalOpen] = useState(false);
    const [testModalSuccess, setTestModalSuccess] = useState(false);
    const [testModalMessage, setTestModalMessage] = useState('');
    const [submitting, setSubmitting] = useState(false);
    const [errors, setErrors] = useState<Partial<Record<keyof FormData, string>>>({});

    const effectiveMode = mode ?? (editItem ? 'edit' : 'create');
    const isEdit = effectiveMode === 'edit';
    const isView = effectiveMode === 'view';

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
                    autoCollectOnSave: false,
                });
            } else {
                setForm(EMPTY_FORM);
            }
            setTestModalOpen(false);
            setTesting(false);
            setErrors({});
            setShowPassword(false);
        }
    }, [open, editItem]);

    const updateField = <K extends keyof FormData>(field: K, value: FormData[K]) => {
        setForm((prev) => {
            const next = {...prev, [field]: value};
            if (field === 'type' && value && !isEdit) {
                next.port = DEFAULT_PORTS[value as DataSourceType];
                if (!DB_TYPES_WITHOUT_SCHEMA.has(value as DataSourceType)) {
                    next.schemaName = prev.schemaName || (value === DataSourceTypeEnum.POSTGRESQL ? 'public' : '');
                } else {
                    next.schemaName = '';
                }
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
        if (form.type && !DB_TYPES_WITHOUT_SCHEMA.has(form.type) && !form.schemaName.trim()) {
            nextErrors.schemaName = '该类型数据源必须填写 Schema';
        }
        if (!form.username.trim()) nextErrors.username = '请输入用户名';
        if (!isEdit && !form.password) nextErrors.password = '请输入密码';
        if (isEdit && form.passwordChanged && !form.password) nextErrors.password = '请输入新密码';
        setErrors(nextErrors);
        return Object.keys(nextErrors).length === 0;
    };

    const handleTest = async () => {
        if (!validate()) return;

        // 编辑模式下未修改密码时提示
        if (isEdit && !form.password) {
            setTestModalSuccess(false);
            setTestModalMessage('编辑模式下测试连接需要输入密码');
            setTestModalOpen(true);
            return;
        }

        setTesting(true);
        try {
            const result = await testConnection({
                type: form.type as DataSourceType,
                host: form.host.trim(),
                port: Number(form.port),
                databaseName: form.databaseName.trim(),
                schemaName: form.schemaName.trim() || undefined,
                username: form.username.trim(),
                password: form.password,
            });
            setTestModalSuccess(result.data.success);
            setTestModalMessage(result.data.message || (result.data.success ? '连接正常' : '连接失败'));
        } catch {
            // API 拦截器已弹 message.error，这里确保弹窗展示失败
            setTestModalSuccess(false);
            setTestModalMessage('测试请求失败，请检查参数');
        } finally {
            setTesting(false);
            setTestModalOpen(true);
        }
    };

    const handleSubmit = async () => {
        if (!validate() || !onSubmit) return;
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
                autoCollectOnSave: form.autoCollectOnSave,
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
                autoCollectOnSave: form.autoCollectOnSave,
            };
        try {
            await onSubmit(payload as DataSourceCreateRequest | DataSourceUpdateRequest);
            onClose();
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <>
            <Drawer
                open={open}
                title={isView ? '详情' : (isEdit ? '编辑数据源' : '新增数据源')}
                onClose={onClose}
                footer={
                    isView ? (
                        <DsButton
                            variant="secondary"
                            data-testid="datasource-drawer-close-btn"
                            onClick={onClose}
                        >
                            关闭
                        </DsButton>
                    ) : (
                        <>
                            <DsButton
                                variant="secondary"
                                data-testid="datasource-drawer-test-btn"
                                onClick={handleTest}
                                disabled={testing}
                                loading={testing}
                            >
                                测试连接
                            </DsButton>
                            <DsButton
                                variant="secondary"
                                data-testid="datasource-drawer-cancel-btn"
                                onClick={onClose}
                            >
                                取消
                            </DsButton>
                            <DsButton
                                data-testid="datasource-drawer-save-btn"
                                onClick={handleSubmit}
                                disabled={submitting}
                                loading={submitting}
                            >
                                保存
                            </DsButton>
                        </>
                    )
                }
            >
                <div className="space-y-ds-4">
                        <div>
                            <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                数据源名称 <span className="text-ds-danger">*</span>
                            </label>
                            <input
                                data-testid="datasource-name-input"
                                value={form.name}
                                onChange={(e) => updateField('name', e.target.value)}
                                disabled={isEdit || isView}
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
                                    disabled={isView}
                                    className="w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary disabled:bg-ds-bg-disabled disabled:text-ds-text-muted focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
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
                                    disabled={isView}
                                    className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary disabled:bg-ds-bg-disabled disabled:text-ds-text-muted focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                                    placeholder={form.type ? String(DEFAULT_PORTS[form.type]) : '3306'}
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
                                disabled={isView}
                                className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary disabled:bg-ds-bg-disabled disabled:text-ds-text-muted focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                                placeholder="例如：192.168.1.10 或 mysql.example.com"
                            />
                            {errors.host && <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.host}</p>}
                        </div>

                        <div
                            className={`grid gap-ds-4 ${form.type && !DB_TYPES_WITHOUT_SCHEMA.has(form.type) ? 'grid-cols-2' : 'grid-cols-1'}`}>
                            <div>
                                <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                    数据库名 <span className="text-ds-danger">*</span>
                                </label>
                                <input
                                    data-testid="datasource-database-input"
                                    value={form.databaseName}
                                    onChange={(e) => updateField('databaseName', e.target.value)}
                                    disabled={isView}
                                    className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary disabled:bg-ds-bg-disabled disabled:text-ds-text-muted focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                                    placeholder="例如：orders"
                                />
                                {errors.databaseName &&
                                    <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.databaseName}</p>}
                            </div>
                            {form.type && !DB_TYPES_WITHOUT_SCHEMA.has(form.type) && (
                                <div>
                                    <label
                                        className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                        Schema <span className="text-ds-danger">*</span>
                                    </label>
                                    <input
                                        data-testid="datasource-schema-input"
                                        value={form.schemaName}
                                        onChange={(e) => updateField('schemaName', e.target.value)}
                                        disabled={isView}
                                        className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary disabled:bg-ds-bg-disabled disabled:text-ds-text-muted focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                                        placeholder="例如：public"
                                    />
                                    {errors.schemaName &&
                                        <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.schemaName}</p>}
                                </div>
                            )}
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
                                    disabled={isView}
                                    className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary disabled:bg-ds-bg-disabled disabled:text-ds-text-muted focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
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
                                        disabled={isView}
                                        className="w-full pl-ds-3 pr-9 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary disabled:bg-ds-bg-disabled disabled:text-ds-text-muted focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                                        placeholder={(isEdit || isView) && !form.passwordChanged ? '********' : '请输入密码'}
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
                                disabled={isView}
                                rows={3}
                                className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary disabled:bg-ds-bg-disabled disabled:text-ds-text-muted focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors resize-none"
                                placeholder="可选：填写数据源用途或备注"
                            />
                        </div>

                        {!isEdit && (
                            <label className="flex items-center gap-ds-2 cursor-pointer">
                                <input
                                    type="checkbox"
                                    checked={form.autoCollectOnSave}
                                    onChange={(e) => updateField('autoCollectOnSave', e.target.checked)}
                                    disabled={isView}
                                    className="w-4 h-4 text-ds-accent border-ds-border-subtle rounded focus:ring-ds-accent disabled:opacity-60"
                                />
                                <span className="text-ds-small text-ds-text-secondary">保存后立即采集元数据</span>
                            </label>
                        )}
                    </div>
            </Drawer>

            <TestResultModal
                open={testModalOpen}
                success={testModalSuccess}
                message={testModalMessage}
                onClose={() => setTestModalOpen(false)}
            />
        </>
    );
}
