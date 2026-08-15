/** Sprint 11 F2 RBAC 类型定义 */

/** 角色视图对象（角色管理页列表/详情） */
export interface RoleVO {
    id: string;
    code: string;
    name: string;
    description?: string;
    /** 是否预置角色（预置只读不可删） */
    builtin: boolean;
    /** 功能权限点 code 列表 */
    permissions: string[];
    /** 数据权限默认范围：FULL=全部数据可见 / WHITELIST=仅授权数据可见 */
    dataScope: 'FULL' | 'WHITELIST';
    createdAt: string;
}

/** 权限点（供角色勾选树） */
export interface PermissionVO {
    id: string;
    code: string;
    name: string;
    description?: string;
}

/** 三级数据权限授权项（数据源级/库级/表级） */
export interface DataPermissionGrant {
    datasourceId: string;
    /** 数据库名（空=库级通配） */
    databaseName?: string;
    /** 表名（空=表级通配） */
    tableName?: string;
}

/** 角色数据权限记录（权限配置页回显） */
export interface DataPermissionVO {
    id: string;
    datasourceId: string;
    databaseName?: string;
    tableName?: string;
}

/** 创建自定义角色请求 */
export interface RoleCreateRequest {
    name: string;
    code: string;
    description?: string;
    permissions: string[];
}

/** 编辑自定义角色请求 */
export interface RoleUpdateRequest {
    description?: string;
    permissions: string[];
}

/** 保存角色数据权限请求 */
export interface DataPermissionSaveRequest {
    roleId: string;
    /** 数据权限默认范围 */
    dataScope: 'FULL' | 'WHITELIST';
    grants: DataPermissionGrant[];
}

/** 角色成员（用户选项） */
export interface RoleUser {
    id: string;
    username: string;
    email?: string;
}
