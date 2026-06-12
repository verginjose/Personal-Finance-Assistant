package com.finance.analytics.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.jackson.JsonComponent;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@JsonComponent
public class JacksonTimeConfig {

    public static class LocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {
        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String dateString = p.getValueAsString();
            if (dateString == null || dateString.isBlank()) {
                return null;
            }
            if (dateString.endsWith("Z") || dateString.contains("+")) {
                // Frontend sent ISO string with UTC 'Z' or offset (e.g. 2026-06-12T07:48:08+05:30)
                return OffsetDateTime.parse(dateString).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            } else {
                // Fallback for raw local time string
                return LocalDateTime.parse(dateString);
            }
        }
    }

    public static class LocalDateTimeSerializer extends JsonSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            if (value == null) {
                gen.writeNull();
            } else {
                // Automatically append 'Z' to tell frontend this is a UTC timestamp
                gen.writeString(value.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            }
        }
    }
}
