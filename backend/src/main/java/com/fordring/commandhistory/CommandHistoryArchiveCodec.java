package com.fordring.commandhistory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;

final class CommandHistoryArchiveCodec {
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private CommandHistoryArchiveCodec() {
    }

    static String sha256(byte[] bytes) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            var hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return "sha256:" + hex;
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", error);
        }
    }

    static String canonicalChecksum(ObjectMapper objectMapper, ObjectNode record) {
        try {
            return sha256(CANONICAL_MAPPER.writeValueAsBytes(sort(record)));
        } catch (Exception error) {
            throw new IllegalArgumentException("计算记录校验值失败", error);
        }
    }

    static ObjectNode recordFacts(ObjectMapper objectMapper, ObjectNode record) {
        var copy = record.deepCopy();
        copy.remove("recordChecksum");
        copy.remove("provenanceChain");
        copy.remove("recordOrigin");
        return copy;
    }

    static String utf8(JsonNode node, ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception error) {
            throw new IllegalArgumentException("JSON 序列化失败", error);
        }
    }

    static byte[] utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static String safePathPart(String value) {
        var safe = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "-");
        return safe.isBlank() ? "unknown" : safe;
    }

    private static JsonNode sort(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            var array = CANONICAL_MAPPER.createArrayNode();
            node.forEach(item -> array.add(sort(item)));
            return array;
        }
        var object = CANONICAL_MAPPER.createObjectNode();
        node.fieldNames().forEachRemaining(field -> {
        });
        node.properties().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey()))
                .forEach(entry -> object.set(entry.getKey(), sort(entry.getValue())));
        return object;
    }
}
