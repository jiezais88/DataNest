import {useAuthStore} from '@/store/useAuthStore';
import type {PermissionCode} from '@/constants/permissions';

/**
 * 按钮级权限点判断 hook（Sprint 11 F2）。
 *
 * 权限点来源：登录时后端返回并写入 auth store 的 userInfo.permissions。
 * 用例如：{hasPermission(PERM.DATASOURCE_CREATE) && <Button>新建</Button>}
 * 菜单动态渲染归 F6，本 hook 供页面内按钮/操作显隐使用。
 */
export function usePermission() {
    const userInfo = useAuthStore((s) => s.userInfo);
    const permissions: string[] = userInfo?.permissions ?? [];

    const hasPermission = (code: PermissionCode | string): boolean => permissions.includes(code);
    const hasAnyPermission = (codes: Array<PermissionCode | string>): boolean => codes.some(hasPermission);
    const hasAllPermissions = (codes: Array<PermissionCode | string>): boolean => codes.every(hasPermission);

    return {permissions, hasPermission, hasAnyPermission, hasAllPermissions};
}
