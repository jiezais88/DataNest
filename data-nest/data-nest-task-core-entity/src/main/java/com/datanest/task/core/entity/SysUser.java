package com.datanest.task.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 轻量级系统用户实体，供 task-core 及下游模块联表/批量查询用户名使用。
 * 与 sys_user 表对应，仅暴露 id、username 及审计字段。
 */
@TableName("sys_user")
public class SysUser {

    private Long id;
    private String username;
    private String email;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}
