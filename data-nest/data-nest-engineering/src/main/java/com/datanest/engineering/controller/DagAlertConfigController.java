package com.datanest.engineering.controller;

import com.alibaba.fastjson2.JSON;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.Result;
import com.datanest.engineering.dto.DagAlertConfigPayload;
import com.datanest.task.core.entity.DagAlertConfig;
import com.datanest.task.core.mapper.DagAlertConfigMapper;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * DAG 告警配置接口（全局配置）
 */
@RestController
@RequestMapping("/dev")
public class DagAlertConfigController {

    private static final Pattern EMAIL_SPLIT = Pattern.compile("[;；,，]");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");

    private final DagAlertConfigMapper dagAlertConfigMapper;

    public DagAlertConfigController(DagAlertConfigMapper dagAlertConfigMapper) {
        this.dagAlertConfigMapper = dagAlertConfigMapper;
    }

    @GetMapping("/alert-config")
    public Result<DagAlertConfigPayload> getConfig() {
        DagAlertConfig config = dagAlertConfigMapper.selectGlobal();
        return Result.ok(toPayload(config));
    }

    @PutMapping("/alert-config")
    public Result<DagAlertConfigPayload> updateConfig(@RequestBody DagAlertConfigPayload payload) {
        validate(payload);

        DagAlertConfig config = dagAlertConfigMapper.selectGlobal();
        return Result.ok(toPayload(saveConfig(config, null, payload)));
    }

    /**
     * Sprint 4 review：按 DAG 读取告警配置（含全局回退的完整视图）。
     */
    @GetMapping("/dags/{dagId}/alert-config")
    public Result<DagAlertConfigPayload> getConfigByDag(@PathVariable Long dagId) {
        DagAlertConfig dedicated = dagAlertConfigMapper.selectByDagId(dagId);
        if (dedicated != null) {
            return Result.ok(toPayload(dedicated));
        }
        DagAlertConfig global = dagAlertConfigMapper.selectGlobal();
        return Result.ok(toPayload(global));
    }

    /**
     * Sprint 4 review：按 DAG 保存告警配置。
     */
    @PutMapping("/dags/{dagId}/alert-config")
    public Result<DagAlertConfigPayload> updateConfigByDag(@PathVariable Long dagId,
                                                           @RequestBody DagAlertConfigPayload payload) {
        validate(payload);

        DagAlertConfig config = dagAlertConfigMapper.selectByDagId(dagId);
        return Result.ok(toPayload(saveConfig(config, dagId, payload)));
    }

    private DagAlertConfig saveConfig(DagAlertConfig config, Long dagId, DagAlertConfigPayload payload) {
        LocalDateTime now = LocalDateTime.now();
        if (config == null) {
            config = new DagAlertConfig();
            config.setCreatedAt(now);
        }
        config.setEnabled(Boolean.TRUE.equals(payload.getEnabled()) ? 1 : 0);
        config.setRecipients(payload.getRecipients());
        config.setTriggerConditions(payload.getTriggerConditions() == null
                ? null : JSON.toJSONString(payload.getTriggerConditions()));
        config.setTimeoutMinutes(payload.getTimeoutMinutes());
        config.setDagId(dagId);
        config.setUpdatedAt(now);

        if (config.getId() == null) {
            dagAlertConfigMapper.insert(config);
        } else {
            dagAlertConfigMapper.updateById(config);
        }
        return config;
    }

    private void validate(DagAlertConfigPayload payload) {
        if (Boolean.TRUE.equals(payload.getEnabled()) && !StringUtils.hasText(payload.getRecipients())) {
            return; // 允许启用但收件人为空，此时不报错但发不出邮件
        }
        if (StringUtils.hasText(payload.getRecipients())) {
            for (String email : EMAIL_SPLIT.split(payload.getRecipients())) {
                String trimmed = email.trim();
                if (!StringUtils.hasText(trimmed)) continue;
                if (!EMAIL_PATTERN.matcher(trimmed).matches()) {
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR, "收件人邮箱格式不正确: " + trimmed);
                }
            }
        }
        if (payload.getTriggerConditions() != null) {
            for (String c : payload.getTriggerConditions()) {
                if (!List.of("FAILURE", "TIMEOUT", "SUCCESS").contains(c)) {
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR, "非法触发条件: " + c);
                }
            }
        }
    }

    private DagAlertConfigPayload toPayload(DagAlertConfig config) {
        if (config == null) {
            DagAlertConfigPayload empty = new DagAlertConfigPayload();
            empty.setEnabled(false);
            empty.setTriggerConditions(List.of());
            empty.setTimeoutMinutes(30);
            return empty;
        }
        DagAlertConfigPayload p = new DagAlertConfigPayload();
        p.setId(config.getId());
        p.setEnabled(config.getEnabled() != null && config.getEnabled() == 1);
        p.setRecipients(config.getRecipients());
        p.setTriggerConditions(parseConditions(config.getTriggerConditions()));
        p.setTimeoutMinutes(config.getTimeoutMinutes());
        p.setDagId(config.getDagId());
        p.setCreatedAt(config.getCreatedAt());
        p.setUpdatedAt(config.getUpdatedAt());
        return p;
    }

    private List<String> parseConditions(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            return JSON.parseArray(json, String.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
