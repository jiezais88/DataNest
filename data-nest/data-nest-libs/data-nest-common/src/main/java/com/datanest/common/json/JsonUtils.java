package com.datanest.common.json;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局 JSON 工具类（Jackson 3，Spring Boot 4 统一实现）。
 * <p>
 * 内部持有单例 {@link tools.jackson.databind.json.JsonMapper}（静态初始化一次，不重复创建）；
 * 未注入 Spring 容器的 JSON 解析/序列化统一走本工具类（task-core 及无 web 依赖场景）。
 * 需要与 HTTP 响应同款 Long→String 定制时，各服务直接注入 Boot 自动配置的 {@code JsonMapper} Bean。
 * <p>
 * 方法命名保持现有业务调用兼容（parseObject/parseArray/toJSONString）。
 * JSONObject/JSONArray 由 {@link ObjectNode}/{@link ArrayNode} 替代。
 */
public final class JsonUtils {

    private JsonUtils() {
    }

    /** 静态单例 JsonMapper（Jackson 3） */
    public static final tools.jackson.databind.json.JsonMapper MAPPER =
            tools.jackson.databind.json.JsonMapper.builder().build();

    /** 获取底层 JsonMapper（需要高级 API 时使用） */
    public static tools.jackson.databind.json.JsonMapper mapper() {
        return MAPPER;
    }

    /** 解析为 ObjectNode */
    public static ObjectNode parseObject(String text) {
        if (text == null || text.isBlank()) {
            return MAPPER.createObjectNode();
        }
        JsonNode node = MAPPER.readTree(text);
        return node == null || node.isNull() ? MAPPER.createObjectNode() : (ObjectNode) node;
    }

    /** 反序列化为指定类型（原 JSON.parseObject(String, Class)） */
    public static <T> T parseObject(String text, Class<T> clazz) {
        return MAPPER.readValue(text, clazz);
    }

    /** 反序列化为泛型类型（原 JSON.parseObject(String, TypeReference)） */
    public static <T> T parseObject(String text, TypeReference<T> typeRef) {
        return MAPPER.readValue(text, typeRef);
    }

    /** 解析为 ArrayNode（原 JSON.parseArray(String) → JSONArray） */
    public static ArrayNode parseArray(String text) {
        if (text == null || text.isBlank()) {
            return MAPPER.createArrayNode();
        }
        JsonNode node = MAPPER.readTree(text);
        return node == null || node.isNull() ? MAPPER.createArrayNode() : (ArrayNode) node;
    }

    /** 解析为 List<T>（原 JSON.parseArray(String, Class)） */
    public static <T> List<T> parseArray(String text, Class<T> clazz) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }
        return MAPPER.readValue(text, MAPPER.getTypeFactory().constructCollectionType(List.class, clazz));
    }

    /** 序列化为 JSON 字符串（原 JSON.toJSONString(Object)） */
    public static String toJSONString(Object obj) {
        if (obj == null) {
            return "null";
        }
        return MAPPER.writeValueAsString(obj);
    }

    /** 创建空 ObjectNode（原 new JSONObject()） */
    public static ObjectNode createObjectNode() {
        return MAPPER.createObjectNode();
    }

    /** 创建空 ArrayNode（原 new JSONArray()） */
    public static ArrayNode createArrayNode() {
        return MAPPER.createArrayNode();
    }

    /** 从任意 JsonNode 强转 ObjectNode（原 (JSONObject) obj） */
    public static ObjectNode asObject(JsonNode node) {
        return node instanceof ObjectNode on ? on : null;
    }

    /** 从任意 JsonNode 强转 ArrayNode（原 (JSONArray) obj） */
    public static ArrayNode asArray(JsonNode node) {
        return node instanceof ArrayNode an ? an : null;
    }

    // ==================== 便捷取值（缺失/null 返回 null） ====================

    /** 取子节点（原 JSONObject.getJSONObject(key)），非对象返回 null */
    public static ObjectNode getObject(JsonNode node, String key) {
        JsonNode child = node == null ? null : node.get(key);
        return child instanceof ObjectNode on ? on : null;
    }

    /** 取数组节点（原 JSONObject.getJSONArray(key)），非数组返回 null */
    public static ArrayNode getArray(JsonNode node, String key) {
        JsonNode child = node == null ? null : node.get(key);
        return child instanceof ArrayNode an ? an : null;
    }

    /** 取字符串（原 JSONObject.getString(key)） */
    public static String getString(JsonNode node, String key) {
        JsonNode child = node == null ? null : node.get(key);
        if (child == null || child.isNull()) {
            return null;
        }
        return child.isTextual() ? child.asString() : child.toString();
    }

    /** 取 Long（原 JSONObject.getLong(key)） */
    public static Long getLong(JsonNode node, String key) {
        JsonNode child = node == null ? null : node.get(key);
        return child == null || child.isNull() ? null : child.longValue();
    }

    /** 取 Integer（原 JSONObject.getInteger(key)） */
    public static Integer getInteger(JsonNode node, String key) {
        JsonNode child = node == null ? null : node.get(key);
        return child == null || child.isNull() ? null : child.intValue();
    }

    /** 取 Boolean（原 JSONObject.getBoolean(key)） */
    public static Boolean getBoolean(JsonNode node, String key) {
        JsonNode child = node == null ? null : node.get(key);
        return child == null || child.isNull() ? null : child.booleanValue();
    }

    /** 取数组字段反序列化为 List<T>（原 JSONObject.getList(key, Class)） */
    public static <T> List<T> getList(JsonNode node, String key, Class<T> clazz) {
        JsonNode child = node == null ? null : node.get(key);
        if (child == null || !(child instanceof ArrayNode arr)) {
            return null;
        }
        List<T> result = new ArrayList<>(arr.size());
        for (JsonNode item : arr) {
            result.add(MAPPER.treeToValue(item, clazz));
        }
        return result;
    }

    /** 把节点反序列化为目标类型（原 JSONObject.toJavaObject(Class)） */
    public static <T> T toJavaObject(JsonNode node, Class<T> clazz) {
        return MAPPER.treeToValue(node, clazz);
    }

    // ==================== 构建辅助 ====================

    /** 写入字段（原 JSONObject.put(key, Object)）：按类型分发，非标量用 putPOJO 序列化为节点 */
    public static ObjectNode put(ObjectNode node, String key, Object value) {
        if (node == null) {
            return null;
        }
        if (value == null) {
            node.putNull(key);
        } else if (value instanceof JsonNode jn) {
            node.set(key, jn);
        } else if (value instanceof String s) {
            node.put(key, s);
        } else if (value instanceof Boolean b) {
            node.put(key, b);
        } else if (value instanceof Integer i) {
            node.put(key, i);
        } else if (value instanceof Long l) {
            node.put(key, l);
        } else if (value instanceof Short sh) {
            node.put(key, sh);
        } else if (value instanceof Float f) {
            node.put(key, f);
        } else if (value instanceof Double d) {
            node.put(key, d);
        } else if (value instanceof java.math.BigDecimal bd) {
            node.put(key, bd);
        } else {
            node.putPOJO(key, value);
        }
        return node;
    }

    /** 追加元素（原 JSONArray.add(Object)）：按类型分发，非标量用 putPOJO 序列化为节点 */
    public static ArrayNode add(ArrayNode node, Object value) {
        if (node == null) {
            return null;
        }
        if (value == null) {
            node.addNull();
        } else if (value instanceof JsonNode jn) {
            node.add(jn);
        } else if (value instanceof String s) {
            node.add(s);
        } else if (value instanceof Boolean b) {
            node.add(b);
        } else if (value instanceof Integer i) {
            node.add(i);
        } else if (value instanceof Long l) {
            node.add(l);
        } else if (value instanceof Short sh) {
            node.add(sh);
        } else if (value instanceof Float f) {
            node.add(f);
        } else if (value instanceof Double d) {
            node.add(d);
        } else if (value instanceof java.math.BigDecimal bd) {
            node.add(bd);
        } else {
            node.addPOJO(value);
        }
        return node;
    }
}
