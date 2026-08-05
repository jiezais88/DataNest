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
        String ruleObjectType = rule.getObjectType();
        // 防重：同一对象同类告警 60s 内已发过则跳过（终态并发回调可能重复触发）
        if (alertHistoryMapper.countRecent(ruleObjectType, objectId, alertType) > 0) {
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
        String objectName = alertRuleService.resolveObjectName(ruleObjectType, objectId);
        boolean sent = false;
        try {
            sent = mailService.send(recipients, buildSubject(ruleObjectType, objectName, alertType), buildBody(ruleObjectType, objectName, alertType, detail, objectId));
        } catch (Exception e) {
            logger.error("告警邮件发送失败: objectType={}, objectId={}, alertType={}",
                    objectType, objectId, alertType, e);
        }
        saveHistory(rule, objectId, objectName, alertType, recipients, sent ? AlertConstants.SEND_STATUS_SUCCESS : AlertConstants.SEND_STATUS_FAILED);
        return true;
    }

    private void saveHistory(AlertRule rule, Long objectId, String objectName, String alertType, String recipients, String sendStatus) {
        saveHistory(rule, objectId, objectName, alertType, recipients, sendStatus, null);
    }

    private void saveHistory(AlertRule rule, Long objectId, String objectName, String alertType, String recipients, String sendStatus, String summary) {
        try {
            AlertHistory history = new AlertHistory();
            history.setId(IdWorker.getId());
            history.setAlertRuleId(rule.getId());
            history.setObjectType(rule.getObjectType());
            history.setObjectId(objectId);
            history.setAlertType(alertType);
            history.setRecipients(recipients);
            history.setSendStatus(sendStatus);
            history.setSentAt(LocalDateTime.now());
            alertHistoryMapper.insert(history);
            if (StringUtils.hasText(summary)) {
                logger.info("告警历史记录明细: ruleId={}, summary={}", rule.getId(), summary);
            }
        } catch (Exception e) {
            logger.error("告警历史写入失败: ruleId={}", rule.getId(), e);
        }
    }

    private String buildSubject(String objectType, String objectName, String alertType) {
        String prefix = AlertConstants.ALERT_SUCCESS.equals(alertType) ? "[DataNest 通知]" : "[DataNest 告警]";
        String displayType = displayObjectType(objectType);
        String displayAlert = displayAlertType(alertType);
        return String.format("%s %s「%s」%s", prefix, displayType, safeName(objectName), displayAlert);
    }

    private String buildBody(String objectType, String objectName, String alertType, String detail, Long objectId) {
        String displayAlert = displayAlertType(alertType);
        String content = String.join("\n",
                "对象类型：" + displayObjectType(objectType),
                "对象名称：" + safeName(objectName),
                "告警类型：" + displayAlert,
                "触发时间：" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                "详情：" + buildDetail(detail));
        String url = buildObjectUrl(objectType, objectId);
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
            case AlertConstants.OBJECT_TYPE_QUALITY ->
                    "http://localhost:3000/governance/data-quality/jobs/" + objectId;
            default -> null;
        };
    }

    private String displayObjectType(String objectType) {
        return switch (objectType == null ? "" : objectType) {
            case AlertConstants.OBJECT_TYPE_DAG -> AlertConstants.DISPLAY_DAG;
            case AlertConstants.OBJECT_TYPE_SYNC_JOB -> AlertConstants.DISPLAY_SYNC_JOB;
            case AlertConstants.OBJECT_TYPE_COLLECT_TASK -> AlertConstants.DISPLAY_COLLECT_TASK;
            case AlertConstants.OBJECT_TYPE_QUALITY -> AlertConstants.DISPLAY_QUALITY;
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

    /**
     * 质量分级告警明细项（fireBatch 使用）：等级 + 规则名 + 详情。
     */
    public record AlertItem(String level, String ruleName, String detail) {
    }

    /**
     * 批量合并告警：同一次执行批次的多条异常合并为**一封邮件**发送，并为每条异常写一条 alert_history。
     * <p>
     * 区别于 {@link #fire}（单对象单条），fireBatch 汇总 items 到同一封邮件正文逐条列出；
     * 用于质量检查批次收尾时，同一任务下达到告警等级的规则明细统一通知。
     * <p>
     * 幂等：与 fire 一致按「对象 + 触发类型」60s 窗口防重（countRecent），
     * 批次级幂等由调用侧以 alert_sent 标记额外兜底。
     *
     * @return true 表示命中规则并已处理（含幂等跳过）；false 表示无规则/未启用/未命中触发条件/无有效收件人。
     */
    public boolean fireBatch(String objectType, Long objectId, String alertType, List<AlertItem> items) {
        if (items == null || items.isEmpty()) {
            return false;
        }
        AlertRule rule = alertRuleService.resolveRule(objectType, objectId);
        if (!alertRuleService.isEnabled(rule) || !alertRuleService.containsTrigger(rule, alertType)) {
            return false;
        }
        String ruleObjectType = rule.getObjectType();
        if (alertHistoryMapper.countRecent(ruleObjectType, objectId, alertType) > 0) {
            logger.info("批量告警已发送过，跳过: objectType={}, objectId={}, alertType={}",
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
        String objectName = alertRuleService.resolveObjectName(ruleObjectType, objectId);
        boolean sent = false;
        try {
            sent = mailService.send(recipients,
                    buildBatchSubject(ruleObjectType, objectName, alertType, items.size()),
                    buildBatchBody(ruleObjectType, objectName, alertType, items, objectId));
        } catch (Exception e) {
            logger.error("批量告警邮件发送失败: objectType={}, objectId={}, alertType={}",
                    objectType, objectId, alertType, e);
        }
        String sendStatus = sent ? AlertConstants.SEND_STATUS_SUCCESS : AlertConstants.SEND_STATUS_FAILED;
        for (AlertItem item : items) {
            saveHistory(rule, objectId, objectName, alertType, recipients, sendStatus,
                    buildItemSummary(item));
        }
        return true;
    }

    private String buildBatchSubject(String objectType, String objectName, String alertType, int count) {
        String prefix = AlertConstants.ALERT_SUCCESS.equals(alertType) ? "[DataNest 通知]" : "[DataNest 告警]";
        String displayAlert = displayAlertType(alertType);
        return String.format("%s %s「%s」%s（%d 项）", prefix, displayObjectType(objectType),
                safeName(objectName), displayAlert, count);
    }

    private String buildBatchBody(String objectType, String objectName, String alertType,
                                  List<AlertItem> items, Long objectId) {
        StringBuilder sb = new StringBuilder();
        sb.append("对象类型：").append(displayObjectType(objectType)).append("\n");
        sb.append("对象名称：").append(safeName(objectName)).append("\n");
        sb.append("告警类型：").append(displayAlertType(alertType)).append("\n");
        sb.append("触发时间：").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        sb.append("异常明细（共 ").append(items.size()).append(" 项）：").append("\n");
        for (int i = 0; i < items.size(); i++) {
            AlertItem item = items.get(i);
            sb.append("  ").append(i + 1).append(". ")
                    .append("[").append(displayLevel(item.level())).append("] ")
                    .append(safeName(item.ruleName())).append("\n");
            if (StringUtils.hasText(item.detail())) {
                sb.append("     详情：").append(buildDetail(item.detail())).append("\n");
            }
        }
        String url = buildObjectUrl(objectType, objectId);
        if (StringUtils.hasText(url)) {
            sb.append("查看详情：").append(url).append("\n");
        }
        return sb.toString();
    }

    /** 单条 history 记录异常摘要（等级 + 规则名）。 */
    private String buildItemSummary(AlertItem item) {
        return "[" + displayLevel(item.level()) + "] " + safeName(item.ruleName());
    }

    private String displayLevel(String level) {
        if (AlertConstants.QUALITY_LEVEL_SEVERE.equals(level)) {
            return "严重";
        }
        if (AlertConstants.QUALITY_LEVEL_WARNING.equals(level)) {
            return "警告";
        }
        if (AlertConstants.QUALITY_LEVEL_UNAVAILABLE.equals(level)) {
            return "不可用";
        }
        return level == null ? "" : level;
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
