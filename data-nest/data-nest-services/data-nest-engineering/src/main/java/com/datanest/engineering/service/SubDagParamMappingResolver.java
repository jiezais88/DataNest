package com.datanest.engineering.service;

import com.datanest.common.json.JsonUtils;
import com.datanest.engineering.entity.DagExecution;
import com.datanest.engineering.entity.DagNode;
import com.datanest.engineering.mapper.DagNodeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子 DAG 参数下发解析器（Sprint 7 NG5）。
 * 按子 DAG 节点 config 的 paramMappings，把父 DAG 执行上下文中的主参数实际值
 * 映射为子 DAG 的参数覆盖集（manualOverrides），供两条触发链路共用：
 * <ul>
 *   <li>异步（dagSubDagAsyncHandler → SubDagTriggerController）：按父 nodeId 定位节点</li>
 *   <li>同步（NESTED_WORKFLOW 嵌套工作流 → ensure-execution 补齐子执行记录）：
 *       按 subDagId 反查父 DAG 中引用该子 DAG 的节点</li>
 * </ul>
 * 运行时取值失败（主参数不存在/无值）不阻断触发：跳过该映射并记 warn，
 * 与 DagParameterResolver 未定义占位符保留原样的容错语义一致（配置期已由 DagService 校验）。
 */
@Service
public class SubDagParamMappingResolver {

    private static final Logger logger = LoggerFactory.getLogger(SubDagParamMappingResolver.class);

    private final DagNodeMapper dagNodeMapper;
    private final DagParameterService dagParameterService;

    public SubDagParamMappingResolver(DagNodeMapper dagNodeMapper, DagParameterService dagParameterService) {
        this.dagNodeMapper = dagNodeMapper;
        this.dagParameterService = dagParameterService;
    }

    /**
     * 异步触发链路：按父 DAG + 父节点 nodeId 解析子 DAG 参数覆盖集。
     *
     * @return 参数覆盖集；无映射/无有效映射时返回空 Map（调用方按不下发处理）
     */
    public Map<String, Object> resolveForAsyncTrigger(Long parentDagId, String parentNodeId,
                                                      DagExecution parentExecution) {
        if (parentDagId == null || !StringUtils.hasText(parentNodeId)) {
            return Map.of();
        }
        DagNode node = dagNodeMapper.selectByDagId(parentDagId).stream()
                .filter(n -> parentNodeId.equals(n.getNodeId()))
                .findFirst().orElse(null);
        if (node == null || !StringUtils.hasText(node.getConfig())) {
            return Map.of();
        }
        return buildOverrides(parseConfig(node), parentDagId, parentExecution, node.getNodeId());
    }

    /**
     * 同步嵌套工作流链路：按 subDagId 反查父 DAG 中引用该子 DAG 的 SUB_DAG 节点并解析覆盖集。
     * 多个节点引用同一子 DAG 时取首个带 paramMappings 的节点（边界场景，记 warn）。
     *
     * @return 参数覆盖集；无映射/无有效映射时返回空 Map（调用方按不下发处理）
     */
    public Map<String, Object> resolveForNested(Long parentDagId, Long subDagId,
                                                DagExecution parentExecution) {
        if (parentDagId == null || subDagId == null) {
            return Map.of();
        }
        List<DagNode> candidates = dagNodeMapper.selectByDagId(parentDagId).stream()
                .filter(n -> "SUB_DAG".equalsIgnoreCase(n.getNodeType()))
                .filter(n -> {
                    ObjectNode cfg = parseConfig(n);
                    return cfg != null && subDagId.equals(JsonUtils.getLong(cfg, "subDagId"));
                })
                .toList();
        if (candidates.isEmpty()) {
            return Map.of();
        }
        if (candidates.size() > 1) {
            logger.warn("父 DAG 存在多个引用同一子 DAG 的节点，参数映射取首个: parentDagId={}, subDagId={}, 命中节点数={}",
                    parentDagId, subDagId, candidates.size());
        }
        DagNode node = candidates.get(0);
        return buildOverrides(parseConfig(node), parentDagId, parentExecution, node.getNodeId());
    }

    /** 按节点 paramMappings 从父执行上下文构建子 DAG 参数覆盖集 */
    private Map<String, Object> buildOverrides(ObjectNode nodeConfig, Long parentDagId,
                                               DagExecution parentExecution, String nodeId) {
        if (nodeConfig == null) {
            return Map.of();
        }
        ArrayNode mappings = JsonUtils.getArray(nodeConfig, "paramMappings");
        if (mappings == null || mappings.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> parentParams = resolveParentParams(parentDagId, parentExecution);
        Map<String, Object> overrides = new LinkedHashMap<>();
        for (int i = 0; i < mappings.size(); i++) {
            ObjectNode mapping = JsonUtils.asObject(mappings.get(i));
            if (mapping == null) {
                continue;
            }
            String mainKey = normalizeParamName(JsonUtils.getString(mapping, "mainParam"));
            String subKey = normalizeParamName(JsonUtils.getString(mapping, "subParam"));
            if (mainKey == null || subKey == null) {
                logger.warn("子 DAG 参数映射存在空项，已跳过: parentDagId={}, nodeId={}, mapping={}",
                        parentDagId, nodeId, mapping);
                continue;
            }
            Object value = parentParams.get(mainKey);
            if (value == null) {
                logger.warn("子 DAG 参数下发跳过：主 DAG 执行上下文无该参数值: parentDagId={}, nodeId={}, mainParam={}",
                        parentDagId, nodeId, mainKey);
                continue;
            }
            overrides.put(subKey, value);
        }
        if (!overrides.isEmpty()) {
            logger.info("子 DAG 参数下发: parentDagId={}, nodeId={}, overrides={}", parentDagId, nodeId, overrides.keySet());
        }
        return overrides;
    }

    /**
     * 父 DAG 执行参数集：优先取执行记录已落库的 resolvedParams（手动触发）；
     * 为空（cron/嵌套补齐的执行记录）时按默认值+系统变量现算。
     */
    private Map<String, Object> resolveParentParams(Long parentDagId, DagExecution parentExecution) {
        if (parentExecution != null && StringUtils.hasText(parentExecution.getResolvedParams())) {
            try {
                tools.jackson.databind.JsonNode node = JsonUtils.MAPPER.readTree(parentExecution.getResolvedParams());
                Map<String, Object> map = new LinkedHashMap<>();
                if (node instanceof ObjectNode on) {
                    on.properties().forEach(entry -> map.put(entry.getKey(), scalarOrPojo(entry.getValue())));
                }
                return map;
            } catch (Exception e) {
                logger.warn("父执行 resolvedParams 解析失败，按参数默认值现算: executionId={}: {}",
                        parentExecution.getId(), e.getMessage());
            }
        }
        return dagParameterService.resolveParams(parentDagId, null);
    }

    /** JsonNode → 标量值或 POJO（供参数覆盖集透传） */
    private static Object scalarOrPojo(tools.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isFloatingPointNumber()) {
            return node.doubleValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            return node.asString();
        }
        // 对象/数组：序列化回 JSON 字符串（fastjson2 parseObject 原返回 Map/List，语义等价）
        return JsonUtils.toJSONString(node);
    }

    /** 参数名归一化：剥离 "${...}" 包装（"${biz_date}" → "biz_date"），空值返回 null */
    static String normalizeParamName(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String name = raw.trim();
        if (name.startsWith("${") && name.endsWith("}") && name.length() > 3) {
            name = name.substring(2, name.length() - 1).trim();
        }
        return StringUtils.hasText(name) ? name : null;
    }

    private static ObjectNode parseConfig(DagNode node) {
        if (node == null || !StringUtils.hasText(node.getConfig())) {
            return null;
        }
        try {
            return JsonUtils.parseObject(node.getConfig());
        } catch (Exception e) {
            return null;
        }
    }
}
