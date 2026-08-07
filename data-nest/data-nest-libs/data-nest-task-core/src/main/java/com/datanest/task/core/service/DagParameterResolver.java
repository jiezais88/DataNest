package com.datanest.task.core.service;

import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringDagApi;
import com.datanest.engineering.api.dto.DagParamInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DAG 参数解析/占位符替换。
 * Sprint 4 下沉到 task-core，供 engineering 与 worker 共用。
 * 微服务化 3.3：dag_parameter 读取经 EngineeringDagApi 远程获取（RemoteCalls 降级空列表），
 * 占位符替换等纯逻辑保留本地。
 */
@Service
public class DagParameterResolver {

    private static final Logger logger = LoggerFactory.getLogger(DagParameterResolver.class);

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final EngineeringDagApi dagApi;

    public DagParameterResolver(EngineeringDagApi dagApi) {
        this.dagApi = dagApi;
    }

    /**
     * 解析 DAG 执行参数：手动覆盖 > 默认值 > 系统变量
     *
     * @param dagId           DAG ID
     * @param manualOverrides 手动传入的参数
     * @return 合并后的参数 Map
     */
    public Map<String, Object> resolveParams(Long dagId, Map<String, Object> manualOverrides) {
        Map<String, Object> resolved = new LinkedHashMap<>();

        // 系统变量：biz_date 默认昨天（业务日期）
        LocalDateTime now = LocalDateTime.now();
        resolved.put("biz_date", now.minusDays(1).format(DATE_FMT));
        resolved.put("current_time", now.format(TIME_FMT));
        if (dagId != null) {
            resolved.put("dag_id", dagId.toString());
        }

        // 默认值（远程读取 dag_parameter，失败降级空列表，仅靠系统变量与手动覆盖）
        if (dagId != null) {
            List<DagParamInfo> params = RemoteCalls.execute("engineering.dag.parameters", () -> {
                Result<List<DagParamInfo>> result = dagApi.listParameters(dagId);
                return result == null || result.data() == null ? List.of() : result.data();
            }, List.of());
            for (DagParamInfo param : params) {
                String name = param.getParamName();
                if (!resolved.containsKey(name) && StringUtils.hasText(param.getDefaultValue())) {
                    resolved.put(name, param.getDefaultValue());
                }
            }
        }

        // 手动覆盖
        if (manualOverrides != null) {
            for (Map.Entry<String, Object> entry : manualOverrides.entrySet()) {
                if (entry.getKey() != null) {
                    resolved.put(entry.getKey(), entry.getValue());
                }
            }
        }

        return resolved;
    }

    /**
     * 替换字符串中的 ${key} 占位符。
     * 若值用于 SQL 字符串，会自动对单引号转义。
     * 未定义的占位符保留原样并记录 warn。
     */
    public String replacePlaceholders(String raw, Map<String, Object> params) {
        if (!StringUtils.hasText(raw)) {
            return raw;
        }
        Matcher m = PLACEHOLDER_PATTERN.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            Object value = params == null ? null : params.get(key);
            if (value == null) {
                logger.warn("DAG 参数未定义，保留占位符: key={}", key);
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            } else {
                String replacement = escapeSqlString(value.toString());
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 批量替换 Map 中所有字符串值里的占位符。
     */
    public Map<String, Object> replacePlaceholders(Map<String, Object> rawMap, Map<String, Object> params) {
        if (rawMap == null) return new HashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s) {
                result.put(entry.getKey(), replacePlaceholders(s, params));
            } else {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    private String escapeSqlString(String value) {
        if (value == null) return "";
        return value.replace("'", "''");
    }
}
