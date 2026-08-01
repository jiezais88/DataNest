import request from './request';
import type {PageResult, Result} from '../types/common';

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
    request.post<Result<{ token: string; userInfo: { userId: string; username: string; roles: string[] } }>>(
        '/system/auth/login', params, {skipErrorMessage: true});

export const logout = () => request.post('/system/auth/logout');

export const getUsers = (params: {
    page: number;
    pageSize: number;
    keyword?: string;
    roleCode?: string;
    status?: string
}) =>
    request.get<Result<PageResult<UserVO>>>('/system/users', {params});

export const createUser = (params: CreateUserParams) =>
    request.post<Result<UserVO>>('/system/users', params);

export const updateUser = (userId: string, params: UpdateUserParams) =>
    request.put<Result<UserVO>>(`/system/users/${userId}`, params);

export const toggleUserStatus = (userId: string) =>
    request.put(`/system/users/${userId}/toggle`);

export const changePassword = (oldPassword: string, newPassword: string) =>
    request.put('/system/users/password', {oldPassword, newPassword});

export const resetPassword = (userId: string, newPassword: string) =>
    request.put(`/system/users/${userId}/reset-password`, {newPassword});
