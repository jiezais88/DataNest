package com.datanest.common.satoken;

import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Sa-Token 权限扩展接口实现。
 * <p>
 * 登录时将用户角色写入 Token 扩展字段（extra: roles），本实现从当前登录模型中读取。
 * 这样各微服务无需重复查询数据库即可校验 @SaCheckRole。
 */
@Component
public class StpInterfaceImpl implements StpInterface {

    @SuppressWarnings("unchecked")
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        try {
            Object roles = StpUtil.getSession().get("roles");
            if (roles instanceof List<?>) {
                return (List<String>) roles;
            }
        } catch (Exception e) {
            // 未登录或 Session 中无角色信息，返回空列表
        }
        return Collections.emptyList();
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return Collections.emptyList();
    }
}
