import {useEffect, useState} from 'react';
import {Divider, Form, Input, InputNumber, Select, Switch} from 'antd';
import {getSsoConfig, ldapSyncUsers, saveSsoConfig, type SsoConfig} from '@/api/auth';
import DsButton from '@/components/DsButton';
import DsStatusBadge from '@/components/DsStatusBadge';
import {notify} from '@/utils/notify';
import {getErrorMessage} from '@/utils/error';
import {ROLE_OPTIONS} from '@/constants/roles';
import {HiOutlineArrowPath} from 'react-icons/hi2';

const ROLE_VALUES = ROLE_OPTIONS.map((r) => ({value: r.value, label: r.label}));

const MODE_OPTIONS = [
    {value: 'mixed', label: '混合模式（本地账号 + 企业身份）'},
    {value: 'sso-only', label: '仅企业身份登录（本地账号不可用，admin 保底）'},
];

/** 身份认证（Sprint 14 SSO）：登录策略 / OIDC / LDAP / 角色映射 / 密码策略，保存后经 Nacos 热生效 */
export default function AuthConfigPage() {
    const [form] = Form.useForm();
    const [loading, setLoading] = useState(false);
    const [saving, setSaving] = useState(false);
    const [syncing, setSyncing] = useState(false);

    useEffect(() => {
        setLoading(true);
        getSsoConfig()
            .then((cfg) => {
                form.setFieldsValue(cfg);
            })
            .catch(() => notify.error('身份认证配置读取失败'))
            .finally(() => setLoading(false));
    }, [form]);

    const handleSave = async () => {
        try {
            const values = await form.validateFields();
            setSaving(true);
            await saveSsoConfig(values as SsoConfig);
            notify.success('身份认证配置已保存并生效');
        } catch (e) {
            const msg = getErrorMessage(e, '保存失败');
            if (msg) notify.error(msg);
        } finally {
            setSaving(false);
        }
    };

    const handleSync = async () => {
        setSyncing(true);
        try {
            const result = await ldapSyncUsers();
            notify.success(`同步完成：共 ${result.data.total} 人，新增 ${result.data.created}、更新 ${result.data.updated}` +
                (result.data.skipped ? `、跳过 ${result.data.skipped}` : ''));
        } catch (e) {
            notify.error(getErrorMessage(e, 'LDAP 用户同步失败'));
        } finally {
            setSyncing(false);
        }
    };

    const sectionCls = 'bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-5';

    return (
        <div className="flex flex-col">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">身份认证</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">
                        配置企业身份登录（SSO/LDAP）、角色映射与本地密码策略，保存后热生效无需重启
                    </p>
                </div>
            </div>

            <Form form={form} layout="vertical" disabled={loading}>
                <div className="space-y-ds-4 mb-ds-8">
                    {/* 登录策略 */}
                    <section className={sectionCls}>
                        <h3 className="text-ds-subhead text-ds-text-primary mb-ds-4">登录策略</h3>
                        <div className="grid grid-cols-1 md:grid-cols-2 gap-x-ds-6">
                            <Form.Item name="enabled" label="SSO 总开关" valuePropName="checked">
                                <Switch checkedChildren="开启" unCheckedChildren="关闭"/>
                            </Form.Item>
                            <Form.Item name="mode" label="登录模式">
                                <Select options={MODE_OPTIONS} placeholder="选择登录模式"/>
                            </Form.Item>
                        </div>
                        <Form.Item name="frontendUrl" label="前端地址（SSO 回调后重定向目标）">
                            <Input placeholder="http://localhost:3000"/>
                        </Form.Item>
                    </section>

                    {/* OIDC */}
                    <section className={sectionCls}>
                        <h3 className="text-ds-subhead text-ds-text-primary mb-ds-4">OIDC 企业身份（授权码流程）</h3>
                        <div className="grid grid-cols-1 md:grid-cols-2 gap-x-ds-6">
                            <Form.Item name={['oidc', 'enabled']} label="启用 OIDC" valuePropName="checked">
                                <Switch checkedChildren="开启" unCheckedChildren="关闭"/>
                            </Form.Item>
                            <Form.Item name={['oidc', 'issuer']} label="IdP 发行方（Issuer）">
                                <Input placeholder="https://idp.example.com"/>
                            </Form.Item>
                            <Form.Item name={['oidc', 'authorizationEndpoint']} label="授权端点（留空走 OIDC Discovery）">
                                <Input placeholder="https://idp.example.com/authorize"/>
                            </Form.Item>
                            <Form.Item name={['oidc', 'tokenEndpoint']} label="令牌端点（留空走 OIDC Discovery）">
                                <Input placeholder="https://idp.example.com/token"/>
                            </Form.Item>
                            <Form.Item name={['oidc', 'jwksUri']} label="JWKS 公钥端点（留空走 OIDC Discovery）">
                                <Input placeholder="https://idp.example.com/jwks"/>
                            </Form.Item>
                            <Form.Item name={['oidc', 'clientId']} label="客户端 ID">
                                <Input placeholder="datanest"/>
                            </Form.Item>
                            <Form.Item name={['oidc', 'clientSecret']} label="客户端密钥">
                                <Input.Password placeholder="客户端密钥"/>
                            </Form.Item>
                            <Form.Item name={['oidc', 'scope']} label="请求 Scope">
                                <Input placeholder="openid,profile,email"/>
                            </Form.Item>
                        </div>
                        <Form.Item name={['oidc', 'redirectUri']} label="回调地址">
                            <Input placeholder="http://localhost:8080/api/system/auth/sso/oidc/callback"/>
                        </Form.Item>
                    </section>

                    {/* LDAP */}
                    <section className={sectionCls}>
                        <div className="flex items-center justify-between mb-ds-4">
                            <h3 className="text-ds-subhead text-ds-text-primary">LDAP / AD 域登录</h3>
                            <DsButton variant="secondary" onClick={handleSync} disabled={loading || syncing}
                                      loading={syncing}>
                                <HiOutlineArrowPath size={16}/>
                                同步目录用户
                            </DsButton>
                        </div>
                        <div className="grid grid-cols-1 md:grid-cols-2 gap-x-ds-6">
                            <Form.Item name={['ldap', 'enabled']} label="启用 LDAP" valuePropName="checked">
                                <Switch checkedChildren="开启" unCheckedChildren="关闭"/>
                            </Form.Item>
                            <Form.Item name={['ldap', 'url']} label="LDAP 地址">
                                <Input placeholder="ldap://ldap.example.com:389"/>
                            </Form.Item>
                            <Form.Item name={['ldap', 'baseDn']} label="基础 DN">
                                <Input placeholder="dc=example,dc=com"/>
                            </Form.Item>
                            <Form.Item name={['ldap', 'userSearchBase']} label="用户搜索基（OU）">
                                <Input placeholder="ou=people"/>
                            </Form.Item>
                            <Form.Item name={['ldap', 'bindDn']} label="管理绑定 DN（留空为匿名）">
                                <Input placeholder="cn=admin,dc=example,dc=com"/>
                            </Form.Item>
                            <Form.Item name={['ldap', 'bindPassword']} label="管理绑定密码">
                                <Input.Password placeholder="管理绑定密码"/>
                            </Form.Item>
                            <Form.Item name={['ldap', 'userFilter']} label="用户过滤器（{0} 为用户登录名）">
                                <Input placeholder="(&(objectClass=inetOrgPerson)(uid={0}))"/>
                            </Form.Item>
                            <Form.Item name={['ldap', 'usernameAttribute']} label="用户名属性">
                                <Input placeholder="uid"/>
                            </Form.Item>
                            <Form.Item name={['ldap', 'emailAttribute']} label="邮箱属性">
                                <Input placeholder="mail"/>
                            </Form.Item>
                            <Form.Item name={['ldap', 'displayNameAttribute']} label="显示名属性">
                                <Input placeholder="displayName"/>
                            </Form.Item>
                            <Form.Item name={['ldap', 'groupAttribute']} label="组属性（memberOf 等多值，供角色映射）">
                                <Input placeholder="memberOf"/>
                            </Form.Item>
                        </div>
                    </section>

                    {/* 角色映射 */}
                    <section className={sectionCls}>
                        <h3 className="text-ds-subhead text-ds-text-primary mb-ds-4">角色映射</h3>
                        <Form.Item name={['roleMapping', 'defaultRole']} label="默认角色（未命中规则时）"
                                   className="max-w-[360px]">
                            <Select options={ROLE_VALUES} placeholder="选择默认角色"/>
                        </Form.Item>
                        <Divider plain className="!my-ds-2 text-ds-text-muted">映射规则（命中即覆盖账号当前角色）</Divider>
                        <Form.List name={['roleMapping', 'rules']}>
                            {(fields, {add, remove}) => (
                                <div className="space-y-ds-3">
                                    {fields.map(({key, name, ...restField}) => (
                                        <div key={key} className="grid grid-cols-1 md:grid-cols-12 gap-ds-3 items-start">
                                            <Form.Item {...restField} name={[name, 'claim']} className="md:col-span-3 !mb-0"
                                                       label={key === 0 ? 'Claim 字段' : undefined}>
                                                <Input placeholder="groups"/>
                                            </Form.Item>
                                            <Form.Item {...restField} name={[name, 'value']} className="md:col-span-4 !mb-0"
                                                       label={key === 0 ? '命中值' : undefined}>
                                                <Input placeholder="datanest-engineers"/>
                                            </Form.Item>
                                            <Form.Item {...restField} name={[name, 'roles']} className="md:col-span-4 !mb-0"
                                                       label={key === 0 ? '授予角色' : undefined}>
                                                <Select mode="multiple" options={ROLE_VALUES}
                                                        placeholder="选择角色" maxTagCount={2}/>
                                            </Form.Item>
                                            <div className="md:col-span-1 pt-1 text-right">
                                                <DsButton variant="ghost" onClick={() => remove(name)} aria-label="删除规则">
                                                    删除
                                                </DsButton>
                                            </div>
                                        </div>
                                    ))}
                                    <DsButton variant="secondary" onClick={() => add({claim: 'groups', value: '', roles: []})}>
                                        添加规则
                                    </DsButton>
                                </div>
                            )}
                        </Form.List>
                    </section>

                    {/* 密码策略 */}
                    <section className={sectionCls}>
                        <h3 className="text-ds-subhead text-ds-text-primary mb-ds-4">
                            密码策略 <span className="text-ds-nano text-ds-text-muted ml-1">（仅本地账号生效，企业身份账号不受限）</span>
                        </h3>
                        <div className="grid grid-cols-1 md:grid-cols-3 gap-x-ds-6">
                            <Form.Item name={['passwordPolicy', 'minLength']} label="最小长度">
                                <InputNumber min={4} max={32} className="w-full"/>
                            </Form.Item>
                            <Form.Item name={['passwordPolicy', 'expireDays']} label="密码过期天数（0=不过期）">
                                <InputNumber min={0} max={365} className="w-full"/>
                            </Form.Item>
                            <Form.Item name={['passwordPolicy', 'warnBeforeDays']} label="过期前提醒天数">
                                <InputNumber min={0} max={30} className="w-full"/>
                            </Form.Item>
                            <Form.Item name={['passwordPolicy', 'failMax']} label="连续失败锁定阈值">
                                <InputNumber min={1} max={20} className="w-full"/>
                            </Form.Item>
                            <Form.Item name={['passwordPolicy', 'lockMinutes']} label="锁定分钟数">
                                <InputNumber min={1} max={1440} className="w-full"/>
                            </Form.Item>
                            <div className="flex flex-col justify-end pb-ds-4">
                                <div className="flex items-center gap-ds-4">
                                    <Form.Item name={['passwordPolicy', 'requireUppercase']} valuePropName="checked" className="!mb-0">
                                        <Switch checkedChildren="大写" unCheckedChildren="大写"/>
                                    </Form.Item>
                                    <Form.Item name={['passwordPolicy', 'requireLowercase']} valuePropName="checked" className="!mb-0">
                                        <Switch checkedChildren="小写" unCheckedChildren="小写"/>
                                    </Form.Item>
                                    <Form.Item name={['passwordPolicy', 'requireDigit']} valuePropName="checked" className="!mb-0">
                                        <Switch checkedChildren="数字" unCheckedChildren="数字"/>
                                    </Form.Item>
                                    <Form.Item name={['passwordPolicy', 'requireSpecial']} valuePropName="checked" className="!mb-0">
                                        <Switch checkedChildren="特殊" unCheckedChildren="特殊"/>
                                    </Form.Item>
                                </div>
                                <span className="text-ds-nano text-ds-text-muted mt-1">密码复杂度要求</span>
                            </div>
                        </div>
                    </section>
                </div>
            </Form>

            {/* 底部操作区 */}
            <div
                className="sticky bottom-0 bg-ds-bg-surface/95 backdrop-blur border border-ds-border-subtle px-ds-5 py-ds-3 flex items-center justify-between">
                <div className="text-ds-small text-ds-text-muted flex items-center gap-ds-2">
                    {loading ? (
                        <DsStatusBadge variant="pending" label="配置加载中"/>
                    ) : (
                        <DsStatusBadge variant="success" label="配置已加载 · 保存后热生效"/>
                    )}
                </div>
                <div className="flex items-center gap-ds-2">
                    <DsButton variant="secondary" onClick={() => form.resetFields()} disabled={loading || saving}>
                        重置
                    </DsButton>
                    <DsButton onClick={handleSave} disabled={loading} loading={saving}>
                        保存配置
                    </DsButton>
                </div>
            </div>
        </div>
    );
}
