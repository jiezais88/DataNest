import {ENGINEERING_WRITE_ROLES} from '../constants/roles';
import {useHasRole} from './useHasRole';

/**
 * 当前用户是否可对 DAG/同步任务/项目 做写操作（= 工程模块写权限）。
 * 治理员(GOVERNANCE_ADMIN) + 分析师(DATA_ANALYST) 只读。
 *
 * PRD §9 权限矩阵：DATA_ENGINEER / SUPER_ADMIN 有写权限。
 */
export function useCanEdit(): boolean {
    return useHasRole(...ENGINEERING_WRITE_ROLES);
}
