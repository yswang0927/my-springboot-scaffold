package com.myweb.util;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.TimeZone;

import org.springframework.util.StringUtils;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.github.sisyphsu.dateparser.DateParserUtils;

/**
 * 对 ObjectMapper进行一些特殊的增强配置
 */
public class JsonObjectMapper extends ObjectMapper {

    public static final String DATE_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";
    public static final String DATE_FORMAT = "yyyy-MM-dd";
    public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    private static final JsonObjectMapper INSTANCE = new JsonObjectMapper();

    public JsonObjectMapper() {
        this(System.getProperty("user.timezone"));
    }

    public JsonObjectMapper(String timeZone) {
        customizeObjectMapper(this, StringUtils.hasText(timeZone) ? timeZone : DEFAULT_TIMEZONE);
    }

    public static String stringify(Object value) {
        if (value == null) {
            return null;
        }

        try {
            return INSTANCE.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static byte[] toBytes(Object value) {
        if (value == null) {
            return null;
        }

        try {
            return INSTANCE.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static <T> T parse(String json, Class<T> valueType) {
        if (json == null || json.isEmpty()) {
            return null;
        }

        try {
            return INSTANCE.readValue(json, valueType);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static <T> T parse(byte[] jsonBytes, Class<T> valueType) {
        if (jsonBytes == null || jsonBytes.length == 0) {
            return null;
        }

        try {
            return INSTANCE.readValue(jsonBytes, valueType);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static void customizeObjectMapper(ObjectMapper objectMapper, String timeZone) {
        objectMapper.setDateFormat(new SimpleDateFormat(DATE_TIME_FORMAT));
        objectMapper.setTimeZone(TimeZone.getTimeZone(ZoneId.of(timeZone)));
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // jackson在进行序列化时，会默认使用getter方法获取字段进行序列化，这是不想要的，只要序列化Field
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
        objectMapper.setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE);
        objectMapper.setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);
        objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        objectMapper.setVisibility(PropertyAccessor.CREATOR, JsonAutoDetect.Visibility.ANY);
        objectMapper.setVisibility(PropertyAccessor.SETTER, JsonAutoDetect.Visibility.ANY);

        // 统一的自定义模块
        SimpleModule simpleModule = new SimpleModule();

        simpleModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DateTimeFormatter.ofPattern(DATE_TIME_FORMAT)));
        simpleModule.addSerializer(LocalDate.class, new LocalDateSerializer(DateTimeFormatter.ofPattern(DATE_FORMAT)));
        simpleModule.addSerializer(Instant.class, new InstantSerializer2(timeZone));

        simpleModule.addDeserializer(Date.class, new DateDeserializer2());
        simpleModule.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer2());
        simpleModule.addDeserializer(LocalDate.class, new LocalDateDeserializer2());
        simpleModule.addDeserializer(Instant.class, new InstantDeserializer2(timeZone));

        // 处理大数据类型，防止丢失精度
        /*
        simpleModule.addSerializer(Long.class, ToStringSerializer.instance);
        simpleModule.addSerializer(Long.TYPE, ToStringSerializer.instance);
        simpleModule.addSerializer(BigInteger.class, new ToStringSerializer(BigInteger.class));
        simpleModule.addSerializer(BigDecimal.class, new ToStringSerializer(BigDecimal.class));
        */

        objectMapper.registerModules(new JavaTimeModule(), simpleModule);
    }

    static class DateDeserializer2 extends JsonDeserializer<Date> {
        @Override
        public Date deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException {
            String dateStr = jsonParser.getText();
            if (!StringUtils.hasText(dateStr)) {
                return null;
            }
            return DateParserUtils.parseDate(dateStr);
        }
    }

    static class LocalDateTimeDeserializer2 extends JsonDeserializer<LocalDateTime> {
        @Override
        public LocalDateTime deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException {
            String dateStr = jsonParser.getText();
            if (!StringUtils.hasText(dateStr)) {
                return null;
            }
            return DateParserUtils.parseDateTime(dateStr);
        }
    }

    static class LocalDateDeserializer2 extends JsonDeserializer<LocalDate> {
        @Override
        public LocalDate deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException {
            String dateStr = jsonParser.getText();
            if (!StringUtils.hasText(dateStr)) {
                return null;
            }
            return DateParserUtils.parseDateTime(dateStr).toLocalDate();
        }
    }

    static class InstantSerializer2 extends JsonSerializer<Instant> {
        private final DateTimeFormatter formatter;

        public InstantSerializer2(String timeZone) {
            this.formatter = DateTimeFormatter.ofPattern(DATE_TIME_FORMAT).withZone(ZoneId.of(timeZone));
        }

        @Override
        public void serialize(Instant instant, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
            if (instant != null) {
                jsonGenerator.writeString(this.formatter.format(instant));
            }
        }
    }

    static class InstantDeserializer2 extends JsonDeserializer<Instant> {
        private final String timeZone;
        private final ZoneOffset zoneOffset;

        public InstantDeserializer2(String timeZone) {
            this.timeZone = timeZone;
            // ZoneOffset格式：+08:00
            ZoneId zoneId = ZoneId.of(this.timeZone);
            this.zoneOffset = zoneId.getRules().getOffset(Instant.now());
        }

        @Override
        public Instant deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException {
            String dateStr = jsonParser.getText();
            if (!StringUtils.hasText(dateStr)) {
                return null;
            }
            return DateParserUtils.parseDateTime(dateStr).toInstant(this.zoneOffset);
        }
    }

}
