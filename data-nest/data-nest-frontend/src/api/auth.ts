import request from './request';
import type {PageResult, Result} from '@/types/common';

export interface LoginParams {
    username: string;
    password: string;
    rememberMe: boolean;
}

export interface UserVO {
    id: string;
    username: string;
    email: string;
    phone: string;
    enabled: boolean;
    /** 认证来源：LOCAL / OIDC / LDAP（Sprint 14 SSO） */
    authSource?: string;
    /** IdP 唯一标识（OIDC sub / LDAP dn） */
    ssoSubject?: string;
    /** 登录锁定截止时间（LOCAL 用户连续失败锁定） */
    lockedUntil?: string;
    roles: string[];
    createdAt: string;
    updatedAt: string;
    createdByName?: string;
    updatedByName?: string;
}

export interface CreateUserParams {
    username: string;
    password: string;
    roles: string[];
    email?: string;
    phone?: string;
}

export interface UpdateUserParams {
    password?: string;
    roles?: string[];
    email?: string;
    phone?: string;
}

export interface LoginUserInfo {
    userId: string;
    username: string;
    roles: string[];
    permissions?: string[];
    /** Sprint 14：密码过期时登录返回 true，前端需强制跳转改密页 */
    mustChangePwd?: boolean;
}

export const login = (params: LoginParams) =>
    request.post<Result<{ token: string; userInfo: LoginUserInfo }>>(
        '/system/auth/login', params, {skipErrorMessage: true});

export const logout = () => request.post('/system/auth/logout');

/** 当前登录用户最新信息（PM-14：进入应用时刷新 roles/permissions 快照，无需重新登录） */
export const getMe = () =>
    request.get<Result<LoginUserInfo>>('/system/auth/me');

/** 个人中心：当前登录用户完整资料（含邮箱/手机号/创建时间） */
export interface UserProfile {
    userId: string;
    username: string;
    email?: string;
    phone?: string;
    roles: string[];
    createdAt?: string;
}

export const getProfile = () =>
    request.get<Result<UserProfile>>('/system/auth/profile').then(r => r.data);

/** 更新当前用户资料（仅邮箱/手机号；空字符串表示清空，null 表示不修改） */
export interface ProfileUpdateParams {
    email?: string;
    phone?: string;
}

export const updateProfile = (params: ProfileUpdateParams) =>
    request.put<Result<void>>('/system/auth/profile', params);

export const getUsers = (params: {
    page: number;
    pageSize: number;
    keyword?: string;
    roleCode?: string;
    status?: string
}) =>
    request.get<Result<PageResult<UserVO>>>('/system/users', {params});

/** 全部启用用户的轻量选项（Sprint 7 F1 资产目录负责人选择器，治理员/超管；不要求邮箱） */
export const getUserOptions = (keyword?: string) =>
    request.get<Result<{ id: string; username: string; email?: string }[]>>(
        '/system/users/options', {params: {keyword}}).then(r => r.data);

export const createUser = (params: CreateUserParams) =>
    request.post<Result<UserVO>>('/system/users', params);

export const updateUser = (userId: string, params: UpdateUserParams) =>
    request.put<Result<UserVO>>(`/system/users/${userId}`, params);

export const toggleUserStatus = (userId: string) =>
    request.put(`/system/users/${userId}/toggle`);

export const changePassword = (oldPassword: string, newPassword: string, confirmNewPassword: string) =>
    request.put('/system/users/password', {oldPassword, newPassword, confirmNewPassword});

export const resetPassword = (userId: string, newPassword: string) =>
    request.put(`/system/users/${userId}/reset-password`, {newPassword});

// ============ Sprint 14 SSO + 认证安全 ============

/** 登录页 SSO 状态（公开接口：enabled/mode/oidcEnabled/ldapEnabled） */
export interface SsoStatus {
    enabled: boolean;
    mode: 'mixed' | 'sso-only';
    oidcEnabled: boolean;
    ldapEnabled: boolean;
}

export const getSsoStatus = () =>
    request.get<Result<SsoStatus>>('/system/auth/sso/status').then(r => r.data);

/** OIDC 授权（浏览器整页跳转 IdP） */
export const oidcAuthorizeUrl = '/api/system/auth/sso/oidc/authorize';

/** LDAP 域账号登录（与本地登录返回结构一致） */
export const ldapLogin = (username: string, password: string) =>
    request.post<Result<{ token: string; userInfo: LoginUserInfo }>>(
        '/system/auth/sso/ldap/login', {username, password}, {skipErrorMessage: true});

// ---------- 身份认证配置（仅超管 auth:config） ----------

export interface SsoConfig {
    enabled: boolean;
    mode: string;
    frontendUrl?: string;
    oidc: {
        enabled: boolean;
        issuer?: string;
        authorizationEndpoint?: string;
        tokenEndpoint?: string;
        jwksUri?: string;
        clientId?: string;
        clientSecret?: string;
        scope?: string;
        redirectUri?: string;
    };
    ldap: {
        enabled: boolean;
        url?: string;
        baseDn?: string;
        bindDn?: string;
        bindPassword?: string;
        userFilter?: string;
        userSearchBase?: string;
        usernameAttribute?: string;
        emailAttribute?: string;
        displayNameAttribute?: string;
        groupAttribute?: string;
    };
    roleMapping: {
        defaultRole?: string;
        rules: { claim: string; value: string; roles: string[] }[];
    };
    passwordPolicy: {
        minLength: number;
        requireUppercase: boolean;
        requireLowercase: boolean;
        requireDigit: boolean;
        requireSpecial: boolean;
        expireDays: number;
        warnBeforeDays: number;
        failMax: number;
        lockMinutes: number;
    };
}

export const getSsoConfig = () =>
    request.get<Result<SsoConfig>>('/system/auth/sso/config').then(r => r.data);

export const saveSsoConfig = (config: SsoConfig) =>
    request.put<Result<void>>('/system/auth/sso/config', config);

/** LDAP 用户同步（仅超管 auth:sync） */
export const ldapSyncUsers = () =>
    request.post<Result<{ total: number; created: number; updated: number; skipped: number }>>(
        '/system/auth/sso/ldap/sync');

/** 解绑企业身份（仅超管） */
export const unbindSso = (userId: string) =>
    request.put<Result<UserVO>>(`/system/users/${userId}/unbind-sso`);

/** 解除登录锁定（仅超管） */
export const unlockUser = (userId: string) =>
    request.put<Result<UserVO>>(`/system/users/${userId}/unlock`);
