package com.datanest.system;

/**
 * 角色数据权限默认范围常量（Sprint 11 F2，权限配置页「默认范围」显式配置）。
 * <p>
 * {@code FULL}=全部数据可见（白名单忽略，默认，向后兼容）；
 * {@code WHITELIST}=仅授权数据可见（白名单过滤，空白名单=什么都不可见）。
 * 用户多角色合并时，任一角色 FULL 即全量放行。
 */
public final class DataScope {

    public static final String FULL = "FULL";
    public static final String WHITELIST = "WHITELIST";

    private DataScope() {
    }
}
