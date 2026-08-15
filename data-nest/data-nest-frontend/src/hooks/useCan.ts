import {usePermission} from './usePermission';
import type {PermissionCode} from '@/constants/permissions';

/**
 * 权限点组合判断（Sprint 11 F2，替代 useHasRole 做按钮级显隐）。
 *
 * 语义：当前用户拥有任一指定权限点即返回 true。预置角色的权限点关联已在后端种子写好，
 * 因此对预置角色行为与旧 useHasRole 一致；同时支持自定义角色按权限点组合精确控制按钮显隐。
 *
 * 用法：const canWrite = useCan(...GOVERNANCE_WRITE_PERMS);
 */
export function useCan(...perms: PermissionCode[]): boolean {
    return usePermission().hasAnyPermission(perms);
}
