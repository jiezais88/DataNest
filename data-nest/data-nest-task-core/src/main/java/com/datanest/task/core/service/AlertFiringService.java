package com.datanest.task.core.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.datanest.task.core.constant.AlertConstants;
import com.datanest.task.core.entity.AlertHistory;
import com.datanest.task.core.entity.AlertRule;
import com.datanest.task.core.entity.SysUser;
import com.datanest.task.core.mapper.AlertHistoryMapper;
import com.datanest.task.core.mapper.AlertRuleUserMapper;
import com.datanest.task.core.mapper.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 告警触发服务（发邮件 + 写告警历史）。
 * 依赖 MailService，因此只在 worker / job / engineering / governance 中被扫描；
 * system-service 不引入本服务（仅做规则 CRUD）。
 * 各执行节点（DAG 终态 / 同步任务 / 采集任务）在终态时调用 {@link #fire}。
 */
@Service
public class AlertFiringService {

    private static final Logger logger = LoggerFactory.getLogger(AlertFiringService.class);

    private final AlertRuleService alertRuleService;
    private final AlertRuleUserMapper alertRuleUserMapper;
    private final AlertHistoryMapper alertHistoryMapper;
    private final SysUserMapper sysUserMapper;
    private final MailService mailService;

    public AlertFiringService(AlertRuleService alertRuleService,
                              AlertRuleUserMapper alertRuleUserMapper,
                              AlertHistoryMapper alertHistoryMapper,
                              SysUserMapper sysUserMapper,
                              MailService mailService) {
        this.alertRuleService = alertRuleService;
        this.alertRuleUserMapper = alertRuleUserMapper;
        this.alertHistoryMapper = alertHistoryMapper;
        this.sysUserMapper = sysUserMapper;
        this.mailService = mailService;
    }

    /**
     * 触发一次告警。
     *
     * @return true 表示命中规则并已发送；false 表示无规则/未启用/未配置该触发条件/无有效收件人。
     */
    public boolean fire(String objectType, Long objectId, String alertType, String detail) {
        AlertRule rule = alertRuleService.resolveRule(objectType, objectId);
        if (!alertRuleService.isEnabled(rule) || !alertRuleService.containsTrigger(rule, alertType)) {
            return false;
        }
        // 防重：同一对象同类告警 60s 内已发过则跳过（终态并发回调可能重复触发）
        if (alertHistoryMapper.countRecent(rule.getObjectType(), rule.getObjectId(), alertType) > 0) {
            logger.info("告警已发送过，跳过: objectType={}, objectId={}, alertType={}",
                    objectType, objectId, alertType);
            return true;
        }
        List<Long> userIds = alertRuleUserMapper.selectUserIdsByRuleId(rule.getId());
        if (userIds.isEmpty()) {
            return false;
        }
        List<String> emails = sysUserMapper.selectEmailsByIds(userIds).stream()
                .map(SysUser::getEmail)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (emails.isEmpty()) {
            return false;
        }
        String recipients = String.join(";", emails);
        boolean sent = false;
        try {
            sent = mailService.send(recipients, buildSubject(rule, alertType), buildBody(rule, alertType, detail));
        } catch (Exception e) {
            logger.error("告警邮件发送失败: objectType={}, objectId={}, alertType={}",
                    objectType, objectId, alertType, e);
        }
        saveHistory(rule, alertType, recipients, sent ? AlertConstants.SEND_STATUS_SUCCESS : AlertConstants.SEND_STATUS_FAILED);
        return true;
    }

    private void saveHistory(AlertRule rule, String alertType, String recipients, String sendStatus) {
        try {
            AlertHistory history = new AlertHistory();
            history.setId(IdWorker.getId());
            history.setAlertRuleId(rule.getId());
            history.setObjectType(rule.getObjectType());
            history.setObjectId(rule.getObjectId());
            history.setAlertType(alertType);
            history.setRecipients(recipients);
            history.setSendStatus(sendStatus);
            history.setSentAt(LocalDateTime.now());
            alertHistoryMapper.insert(history);
        } catch (Exception e) {
            logger.error("告警历史写入失败: ruleId={}", rule.getId(), e);
        }
    }

    private String buildSubject(AlertRule rule, String alertType) {
        String prefix = AlertConstants.ALERT_SUCCESS.equals(alertType) ? "[DataNest 通知]" : "[DataNest 告警]";
        String displayType = displayObjectType(rule.getObjectType());
        String displayAlert = displayAlertType(alertType);
        return String.format("%s %s「%s」%s", prefix, displayType, safeName(rule.getObjectName()), displayAlert);
    }

    private String buildBody(AlertRule rule, String alertType, String detail) {
        String displayAlert = displayAlertType(alertType);
        String content = String.join("\n",
                "对象类型：" + displayObjectType(rule.getObjectType()),
                "对象名称：" + safeName(rule.getObjectName()),
                "告警类型：" + displayAlert,
                "触发时间：" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                "详情：" + buildDetail(detail));
        String url = buildObjectUrl(rule.getObjectType(), rule.getObjectId());
        if (StringUtils.hasText(url)) {
            content += "\n查看详情：" + url;
        }
        return content;
    }

    private String buildObjectUrl(String objectType, Long objectId) {
        // 占位：前端部署域名可通过配置覆盖；与 DagAlertService 保持一致风格
        return switch (objectType == null ? "" : objectType) {
            case AlertConstants.OBJECT_TYPE_DAG -> "http://localhost:3000/engineering/dags/" + objectId;
            case AlertConstants.OBJECT_TYPE_SYNC_JOB -> "http://localhost:3000/engineering/sync-jobs/" + objectId;
            case AlertConstants.OBJECT_TYPE_COLLECT_TASK ->
                    "http://localhost:3000/governance/collect-tasks/" + objectId;
            default -> null;
        };
    }

    private String displayObjectType(String objectType) {
        return switch (objectType == null ? "" : objectType) {
            case AlertConstants.OBJECT_TYPE_DAG -> AlertConstants.DISPLAY_DAG;
            case AlertConstants.OBJECT_TYPE_SYNC_JOB -> AlertConstants.DISPLAY_SYNC_JOB;
            case AlertConstants.OBJECT_TYPE_COLLECT_TASK -> AlertConstants.DISPLAY_COLLECT_TASK;
            default -> objectType;
        };
    }

    private String displayAlertType(String alertType) {
        return switch (alertType == null ? "" : alertType) {
            case AlertConstants.ALERT_FAILURE -> "执行失败";
            case AlertConstants.ALERT_TIMEOUT -> "执行超时";
            case AlertConstants.ALERT_SUCCESS -> "执行成功";
            default -> alertType;
        };
    }

    private String safeName(String name) {
        return StringUtils.hasText(name) ? name : "未知对象";
    }

    private String buildDetail(String detail) {
        if (!StringUtils.hasText(detail)) {
            return "-";
        }
        String trimmed = detail.trim();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) + "..." : trimmed;
    }
}
