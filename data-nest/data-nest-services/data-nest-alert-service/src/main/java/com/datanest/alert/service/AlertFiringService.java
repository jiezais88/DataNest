package com.datanest.alert.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.datanest.common.constant.AlertConstants;
import com.datanest.alert.entity.AlertHistory;
import com.datanest.alert.entity.AlertRule;
import com.datanest.alert.mapper.AlertHistoryMapper;
import com.datanest.alert.mapper.AlertRuleUserMapper;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.system.api.SystemUserApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

/**
 * 告警触发服务（发邮件 + 写告警历史）。
 * 各执行节点（DAG 终态 / 同步任务 / 采集任务）在终态时通过内部接口调用 {@link #fire}。
 * 微服务化改造：收件人邮箱反查改走 system 内部接口（SystemUserApi），
 * 查询失败记 error 并返回 false（无收件人无法发送，不落历史）。
 */
@Service
public class AlertFiringService {

    private static final Logger logger = LoggerFactory.getLogger(AlertFiringService.class);

    private final AlertRuleService alertRuleService;
    private final AlertRuleUserMapper alertRuleUserMapper;
    private final AlertHistoryMapper alertHistoryMapper;
    private final SystemUserApi systemUserApi;
    private final MailService mailService;

    public AlertFiringService(AlertRuleService alertRuleService,
                              AlertRuleUserMapper alertRuleUserMapper,
                              AlertHistoryMapper alertHistoryMapper,
                              SystemUserApi systemUserApi,
                              MailService mailService) {
        this.alertRuleService = alertRuleService;
        this.alertRuleUserMapper = alertRuleUserMapper;
        this.alertHistoryMapper = alertHistoryMapper;
        this.systemUserApi = systemUserApi;
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
        List<String> emails = fetchEmails(userIds);
        if (emails == null || emails.isEmpty()) {
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

    /**
     * 批量反查收件人邮箱（system 内部接口）。
     *
     * @return 邮箱列表；远端调用失败返回 null（区别于「无有效邮箱」的空列表，调用侧同样不发送）
     */
    private List<String> fetchEmails(List<Long> userIds) {
        // RemoteCalls 统一降级：失败返回 null（语义保留：区别于「无有效邮箱」的空列表，调用侧同样不发送）
        return RemoteCalls.execute("system.emails", () -> {
            Result<List<String>> result = systemUserApi.emails(userIds);
            List<String> emails = result == null || result.data() == null
                    ? Collections.<String>emptyList() : result.data();
            return emails.stream()
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
        }, null);
    }

    private void saveHistory(AlertRule rule, Long objectId, String objectName, String alertType, String recipients, String sendStatus) {
        saveHistory(rule, objectId, objectName, alertType, recipients, sendStatus, null, null);
    }

    private void saveHistory(AlertRule rule, Long objectId, String objectName, String alertType, String recipients, String sendStatus, String summary) {
        saveHistory(rule, objectId, objectName, alertType, recipients, sendStatus, summary, null);
    }

    private void saveHistory(AlertRule rule, Long objectId, String objectName, String alertType, String recipients, String sendStatus, String summary, Long qualityBatchId) {
        try {
            AlertHistory history = new AlertHistory();
            history.setId(IdWorker.getId());
            history.setAlertRuleId(rule.getId());
            history.setRuleName(rule.getName());
            history.setObjectType(rule.getObjectType());
            history.setObjectId(objectId);
            history.setQualityBatchId(qualityBatchId);
            history.setAlertType(alertType);
            history.setRecipients(recipients);
            history.setSendStatus(sendStatus);
            history.setSentAt(LocalDateTime.now());
            history.setSummary(summary);
            alertHistoryMapper.insert(history);
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
            // Sprint 9 F3：CDC 管道详情是抽屉无独立路由，链到列表页
            case AlertConstants.OBJECT_TYPE_CDC_PIPELINE ->
                    "http://localhost:3000/engineering/cdc-pipelines";
            default -> null;
        };
    }

    private String displayObjectType(String objectType) {
        return switch (objectType == null ? "" : objectType) {
            case AlertConstants.OBJECT_TYPE_DAG -> AlertConstants.DISPLAY_DAG;
            case AlertConstants.OBJECT_TYPE_SYNC_JOB -> AlertConstants.DISPLAY_SYNC_JOB;
            case AlertConstants.OBJECT_TYPE_COLLECT_TASK -> AlertConstants.DISPLAY_COLLECT_TASK;
            case AlertConstants.OBJECT_TYPE_QUALITY -> AlertConstants.DISPLAY_QUALITY;
            case AlertConstants.OBJECT_TYPE_CDC_PIPELINE -> AlertConstants.DISPLAY_CDC_PIPELINE;
            default -> objectType;
        };
    }

    private String displayAlertType(String alertType) {
        return switch (alertType == null ? "" : alertType) {
            case AlertConstants.ALERT_FAILURE -> "执行失败";
            case AlertConstants.ALERT_TIMEOUT -> "执行超时";
            case AlertConstants.ALERT_SUCCESS -> "执行成功";
            case AlertConstants.ALERT_LAG_EXCEEDED -> "延迟超阈值";
            case AlertConstants.ALERT_EXTERNAL_STOP -> "外部停止";
            default -> alertType;
        };
    }

    /**
     * 质量分级告警明细项（fireBatch 使用）：等级 + 规则名 + 详情。
     */
    public record AlertItem(String level, String ruleName, String detail) {
    }

    /**
     * 兼容旧签名：不关联质量批次（非质量对象告警使用）。
     */
    public boolean fireBatch(String objectType, Long objectId, String alertType, List<AlertItem> items) {
        return fireBatch(objectType, objectId, alertType, items, null);
    }

    /**
     * 批量合并告警（质量批次关联版）：同一次执行批次的多条异常合并为**一封邮件**发送，并**只写一条** alert_history。
     * <p>
     * 一个批次只落一条告警记录（一个批次对应一条告警），
     * 命中的多条规则通过 {@code summary} 字段聚合（每行一条规则：等级 + 规则名 + 详情），
     * rule_name 存「首个规则名 + 共 n 条」，供批次详情反查展示触发了哪些规则。
     * <p>
     * 幂等：与 fire 一致按「对象 + 触发类型」60s 窗口防重（countRecent），
     * 批次级幂等由调用侧以 alert_sent 标记额外兜底。
     *
     * @param batchId 关联的质量检查批次 ID（质量对象告警时传入，供批次详情反查告警记录；非质量告警传 null）
     * @return true 表示命中规则并已处理（含幂等跳过）；false 表示无规则/未启用/未命中触发条件/无有效收件人。
     */
    public boolean fireBatch(String objectType, Long objectId, String alertType, List<AlertItem> items, Long batchId) {
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
        List<String> emails = fetchEmails(userIds);
        if (emails == null || emails.isEmpty()) {
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
        // 一个批次只落一条告警记录：summary 存每条命中规则明细多行（等级 + 规则名 + 详情），体现本次触发了哪些规则
        saveHistory(rule, objectId, objectName, alertType, recipients, sendStatus, buildBatchSummary(items), batchId);
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

    /**
     * 批次告警聚合明细：把本次触发的多条规则汇总为多行字符串（每行一条规则）。
     * 存于 alert_history.summary，供批次详情展示「触发了哪些规则」。
     * 行格式：{@code [等级] 规则名: 详情}（详情可空）。
     */
    private String buildBatchSummary(List<AlertItem> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (AlertItem item : items) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append('[').append(displayLevel(item.level())).append("] ").append(safeName(item.ruleName()));
            if (StringUtils.hasText(item.detail())) {
                sb.append(": ").append(item.detail());
            }
        }
        return sb.toString();
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
