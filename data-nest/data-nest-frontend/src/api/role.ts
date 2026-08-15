import request from './request';
import type {Result} from '@/types/common';
import type {
    DataPermissionSaveRequest,
    DataPermissionVO,
    PermissionVO,
    RoleCreateRequest,
    RoleUpdateRequest,
    RoleUser,
    RoleVO,
} from '@/types/role';

/** 角色列表（预置 + 自定义，含功能权限点） */
export const getRoles = () => request.get<Result<RoleVO[]>>('/system/roles').then(r => r.data);

/** 创建自定义角色 */
export const createRole = (params: RoleCreateRequest) =>
    request.post<Result<RoleVO>>('/system/roles', params).then(r => r.data);

/** 编辑自定义角色（描述 + 功能权限） */
export const updateRole = (id: string, params: RoleUpdateRequest) =>
    request.put<Result<RoleVO>>(`/system/roles/${id}`, params).then(r => r.data);

/** 删除自定义角色 */
export const deleteRole = (id: string) => request.delete(`/system/roles/${id}`);

/** 权限点清单（供角色勾选树） */
export const getPermissions = () =>
    request.get<Result<PermissionVO[]>>('/system/permissions').then(r => r.data);

/** 保存角色数据权限（全量重建；空 grants 恢复默认全量可见） */
export const saveDataPermission = (params: DataPermissionSaveRequest) =>
    request.post('/system/data-permissions', params);

/** 查询角色数据权限白名单 */
export const getDataPermission = (roleId: string) =>
    request.get<Result<DataPermissionVO[]>>(`/system/data-permissions/${roleId}`).then(r => r.data);

/** 查询角色成员（权限配置页成员 Tab） */
export const getRoleUsers = (roleId: string) =>
    request.get<Result<RoleUser[]>>(`/system/roles/${roleId}/users`).then(r => r.data);

/** 设置角色成员（全量替换） */
export const setRoleUsers = (roleId: string, userIds: string[]) =>
    request.put(`/system/roles/${roleId}/users`, {userIds});
