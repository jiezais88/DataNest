import {ENGINEERING_WRITE_PERMS} from '@/constants/permissions';
import {useCan} from './useCan';

/**
 * 当前用户是否可对 DAG/同步任务/项目 做写操作（= 工程模块写权限）。
 * 治理员(GOVERNANCE_ADMIN) + 分析师(DATA_ANALYST) 只读。
 *
 * Sprint 11 F2：由角色判断迁移为权限点判断（ENGINEERING_WRITE_PERMS），
 * 预置角色行为不变，同时支持自定义角色按权限点控制。
 */
export function useCanEdit(): boolean {
    return useCan(...ENGINEERING_WRITE_PERMS);
}
