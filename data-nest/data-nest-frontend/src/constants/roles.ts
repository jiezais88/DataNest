/**
 * 角色代码与角色组合的唯一出处。历史背景：'SUPER_ADMIN' 等字符串和
 * [角色数组] 曾在 6 个页面 + Sidebar + 两个角色下拉里各写一份。收敛到这里。
 */

export const ROLE = {
    SUPER_ADMIN: 'SUPER_ADMIN',
    DATA_ENGINEER: 'DATA_ENGINEER',
    DATA_ANALYST: 'DATA_ANALYST',
    GOVERNANCE_ADMIN: 'GOVERNANCE_ADMIN',
} as const;

export type RoleCode = (typeof ROLE)[keyof typeof ROLE];

/** 用户管理/创建用户的角色下拉 */
export const ROLE_OPTIONS: { value: RoleCode; label: string }[] = [
    {value: ROLE.SUPER_ADMIN, label: '超级管理员'},
    {value: ROLE.DATA_ENGINEER, label: '数据工程师'},
    {value: ROLE.DATA_ANALYST, label: '数据分析师'},
    {value: ROLE.GOVERNANCE_ADMIN, label: '治理管理员'},
];

/** 工程模块（数据源/同步任务/DAG）写权限。PRD §9：治理员和分析师只读 */
export const ENGINEERING_WRITE_ROLES: RoleCode[] = [ROLE.SUPER_ADMIN, ROLE.DATA_ENGINEER];

/** 治理模块（采集任务/元数据/数据标准）写权限 */
export const GOVERNANCE_WRITE_ROLES: RoleCode[] = [ROLE.SUPER_ADMIN, ROLE.GOVERNANCE_ADMIN];

/** 全部角色（元数据预览等人人可用的只读场景） */
export const ALL_ROLES: RoleCode[] = [ROLE.SUPER_ADMIN, ROLE.DATA_ENGINEER, ROLE.DATA_ANALYST, ROLE.GOVERNANCE_ADMIN];
