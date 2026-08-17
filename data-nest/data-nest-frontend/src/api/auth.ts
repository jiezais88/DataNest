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

export const login = (params: LoginParams) =>
    request.post<Result<{
        token: string;
        userInfo: { userId: string; username: string; roles: string[]; permissions?: string[] }
    }>>(
        '/system/auth/login', params, {skipErrorMessage: true});

export const logout = () => request.post('/system/auth/logout');

/** 当前登录用户最新信息（PM-14：进入应用时刷新 roles/permissions 快照，无需重新登录） */
export const getMe = () =>
    request.get<Result<{ userId: string; username: string; roles: string[]; permissions?: string[] }>>(
        '/system/auth/me');

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
