package com.datanest.task.core.service;

import com.datanest.task.core.entity.SysUser;
import com.datanest.task.core.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 系统用户轻量查询服务（跨模块共享）。
 * 各业务模块列表页可通过它把 createdBy/updatedBy 批量映射为 username。
 */
@Service
public class SysUserService {

    private final SysUserMapper sysUserMapper;

    public SysUserService(SysUserMapper sysUserMapper) {
        this.sysUserMapper = sysUserMapper;
    }

    /**
     * 批量查询 userId → username 映射，过滤 null/0。
     */
    public Map<Long, String> getUsernameMap(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> validIds = ids.stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .collect(Collectors.toList());
        if (validIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<SysUser> users = sysUserMapper.selectByIdList(validIds);
        Map<Long, String> map = new HashMap<>(users.size());
        for (SysUser user : users) {
            if (user.getId() != null && user.getUsername() != null) {
                map.put(user.getId(), user.getUsername());
            }
        }
        return map;
    }
}
