package com.demo.excel.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Jackson JSON 统一封装，替代 FastJSON 调用。
 */
@Component
public class JsonHelper {

    private final ObjectMapper mapper;

    public JsonHelper(ObjectMapper objectMapper) {
        this.mapper = objectMapper;
    }

    public ObjectMapper getMapper() {
        return mapper;
    }

    public ObjectNode createObject() {
        return mapper.createObjectNode();
    }

    public ArrayNode createArray() {
        return mapper.createArrayNode();
    }

    /** null / 空串 / 解析异常 → 空 ObjectNode */
    public ObjectNode parseObjectOrEmpty(String json) {
        if (json == null || json.trim().isEmpty()) {
            return createObject();
        }
        try {
            JsonNode node = mapper.readTree(json);
            return node != null && node.isObject() ? (ObjectNode) node : createObject();
        } catch (Exception ignored) {
            return createObject();
        }
    }

    /** null / 空串 / 解析异常 → 空 ArrayNode */
    public ArrayNode parseArrayOrEmpty(String json) {
        if (json == null || json.trim().isEmpty()) {
            return createArray();
        }
        try {
            JsonNode node = mapper.readTree(json);
            return node != null && node.isArray() ? (ArrayNode) node : createArray();
        } catch (Exception ignored) {
            return createArray();
        }
    }

    public String toJson(Object obj) {
        if (obj == null) {
            return "null";
        }
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON serialize failed", e);
        }
    }

    public Map<String, Object> toMap(Object obj) {
        return mapper.convertValue(obj, new TypeReference<Map<String, Object>>() {});
    }

    public <T> T convert(Object from, Class<T> type) {
        return mapper.convertValue(from, type);
    }

    public ObjectNode toObjectNode(Object value) {
        if (value == null) {
            return createObject();
        }
        if (value instanceof ObjectNode objectNode) {
            return objectNode;
        }
        return mapper.convertValue(value, ObjectNode.class);
    }

    public ArrayNode toArrayNode(Object value) {
        if (value == null) {
            return createArray();
        }
        if (value instanceof ArrayNode arrayNode) {
            return arrayNode;
        }
        if (value instanceof List<?> list) {
            ArrayNode array = createArray();
            for (Object item : list) {
                array.add(toJsonNode(item));
            }
            return array;
        }
        return mapper.convertValue(value, ArrayNode.class);
    }

    public JsonNode toJsonNode(Object value) {
        if (value == null) {
            return mapper.nullNode();
        }
        if (value instanceof JsonNode jsonNode) {
            return jsonNode;
        }
        return mapper.valueToTree(value);
    }

    public ObjectNode getObjectNode(ObjectNode parent, String field) {
        JsonNode node = parent.get(field);
        return node != null && node.isObject() ? (ObjectNode) node : null;
    }

    public ObjectNode getObjectNode(ArrayNode array, int index) {
        if (index < 0 || index >= array.size()) {
            return null;
        }
        JsonNode node = array.get(index);
        return node != null && node.isObject() ? (ObjectNode) node : null;
    }

    public ArrayNode getArrayNode(ObjectNode parent, String field) {
        JsonNode node = parent.get(field);
        return node != null && node.isArray() ? (ArrayNode) node : null;
    }

    public ArrayNode getArrayNode(ArrayNode array, int index) {
        if (index < 0 || index >= array.size()) {
            return null;
        }
        JsonNode node = array.get(index);
        return node != null && node.isArray() ? (ArrayNode) node : null;
    }

    public int getInt(JsonNode node, String field) {
        return getInt(node, field, 0);
    }

    public int getInt(JsonNode node, String field, int defaultValue) {
        if (node == null) {
            return defaultValue;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        return value.asInt(defaultValue);
    }

    public boolean isEmptyObject(JsonNode node) {
        return node == null || node.isNull() || (node.isObject() && node.isEmpty());
    }
}
