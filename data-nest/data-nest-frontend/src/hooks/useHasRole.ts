import {useAuthStore} from '@/store/useAuthStore';
import type {RoleCode} from '@/constants/roles';

/**
 * 当前用户是否拥有任一指定角色。角色代码和组合常量在 src/constants/roles.ts，
 * 页面里不要再手写 'SUPER_ADMIN' 字符串或 roles.includes(...) 判断。
 *
 * 用法：const canWrite = useHasRole(...GOVERNANCE_WRITE_ROLES);
 */
export function useHasRole(...roles: RoleCode[]): boolean {
    const userRoles = useAuthStore(s => s.userInfo?.roles) || [];
    return roles.some(r => userRoles.includes(r));
}
