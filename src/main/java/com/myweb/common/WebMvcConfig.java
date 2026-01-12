package com.myweb.common;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import com.github.sisyphsu.dateparser.DateParserUtils;
import org.msgpack.jackson.dataformat.MessagePackMapper;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    public static final String DATE_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";
    public static final String DATE_FORMAT = "yyyy-MM-dd";

    @Value("${spring.time-zone:Asia/Shanghai}")
    private String timeZone = "Asia/Shanghai";

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 如果前端页面是 SPA 单页路由应用方式，则可以针对性的配置前端url地址映射
        // 也可以使用 FrontendRoutePageRegistrar 实现
        // 示例：
        /*String[] frontendRoutes = {"/user/**", "..."};
        for ( String route : frontendRoutes) {
            registry.addViewController(route).setViewName("forward:/index.html");
        }*/
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // setOrder(-1) 设置静态资源优先级高于 RequestMapping 的优先级
        // 这样当静态资源请求 /static/** 和 RequestMapping(/**) 冲突时，优先使用静态资源处理
        /*registry.addResourceHandler("/static/**", "/assets/**")
                .addResourceLocations("classpath:/static/", "classpath:/assets/");*/
    }

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        // 增加支持 msgpack 协议
        ObjectMapper msgpackMapper = new MessagePackMapper();
        initCustomizeObjectMapper(msgpackMapper);
        MappingJackson2HttpMessageConverter msgpackConverter = new MappingJackson2HttpMessageConverter(msgpackMapper);
        msgpackConverter.setSupportedMediaTypes(List.of(
                new MediaType("application", "x-msgpack"),
                new MediaType("application", "msgpack")
        ));

        // 支持 YAML request-payload 格式
        // @PostMapping(value="...", consumes = "application/x-yaml")
        /*YAMLMapper yamlMapper = new YAMLMapper();
        initCustomizeObjectMapper(yamlMapper);
        MappingJackson2HttpMessageConverter yamlConverter = new MappingJackson2HttpMessageConverter(yamlMapper);
        yamlConverter.setSupportedMediaTypes(List.of(
                new MediaType("application", "x-yaml"),
                new MediaType("text", "yaml")
        ));*/

        converters.add(msgpackConverter);
        //converters.add(yamlConverter);
    }

    /*@Bean
    public WebMvcRegistrations webMvcRegistrations() {
        return new WebMvcRegistrations() {
            @Override
            public RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
                return new RequestMappingHandlerMapping() {
                    @Override
                    protected void initHandlerMethods() {
                        // 调整 RequestMapping 的优先级低于上面的静态资源处理器优先级
                        // 这样当静态资源请求 /static/** 和 RequestMapping(/**) 冲突时，优先使用静态资源处理
                        setOrder(1);
                        super.initHandlerMethods();
                    }
                };
            }
        };
    }*/

    /**
     * 自定义增强 ObjectMapper
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        ObjectMapper objectMapper = builder.build();
        initCustomizeObjectMapper(objectMapper);
        return objectMapper;
    }

    private void initCustomizeObjectMapper(ObjectMapper objectMapper) {
        objectMapper.setDateFormat(new SimpleDateFormat(DATE_TIME_FORMAT));
        objectMapper.setTimeZone(TimeZone.getTimeZone(ZoneId.of(this.timeZone)));
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
        simpleModule.addSerializer(Instant.class, new InstantSerializer2(this.timeZone));

        simpleModule.addDeserializer(Date.class, new DateDeserializer2());
        simpleModule.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer2());
        simpleModule.addDeserializer(LocalDate.class, new LocalDateDeserializer2());
        simpleModule.addDeserializer(Instant.class, new InstantDeserializer2(this.timeZone));

        // 处理大数据类型，防止丢失精度
        simpleModule.addSerializer(Long.class, ToStringSerializer.instance);
        simpleModule.addSerializer(Long.TYPE, ToStringSerializer.instance);
        simpleModule.addSerializer(BigInteger.class, new ToStringSerializer(BigInteger.class));
        simpleModule.addSerializer(BigDecimal.class, new ToStringSerializer(BigDecimal.class));

        objectMapper.registerModules(new JavaTimeModule(), simpleModule);
    }

    static class DateDeserializer2 extends JsonDeserializer<Date> {
        @Override
        public Date deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException, JacksonException {
            String dateStr = jsonParser.getText();
            if (!StringUtils.hasText(dateStr)) {
                return null;
            }
            return DateParserUtils.parseDate(dateStr);
        }
    }

    static class LocalDateTimeDeserializer2 extends JsonDeserializer<LocalDateTime> {
        @Override
        public LocalDateTime deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException, JacksonException {
            String dateStr = jsonParser.getText();
            if (!StringUtils.hasText(dateStr)) {
                return null;
            }
            return DateParserUtils.parseDateTime(dateStr);
        }
    }

    static class LocalDateDeserializer2 extends JsonDeserializer<LocalDate> {
        @Override
        public LocalDate deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException, JacksonException {
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
        public Instant deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException, JacksonException {
            String dateStr = jsonParser.getText();
            if (!StringUtils.hasText(dateStr)) {
                return null;
            }
            return DateParserUtils.parseDateTime(dateStr).toInstant(this.zoneOffset);
        }
    }

}
