package com.researchhub.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

public final class Values {
    private static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    private Values() { }

    public static String id() { return UUID.randomUUID().toString(); }
    public static Timestamp now() { return timestamp(Instant.now()); }
    public static Timestamp timestamp(Instant instant) { return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC)); }

    public static String hash(String text) { return hash(text.getBytes(StandardCharsets.UTF_8)); }

    public static String hash(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize internal value", e);
        }
    }
}
