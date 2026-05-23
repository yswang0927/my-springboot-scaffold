package com.myweb.common;

import java.util.List;

import org.msgpack.jackson.dataformat.MessagePackMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myweb.util.JsonObjectMapper;

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
        JsonObjectMapper.customizeObjectMapper(msgpackMapper, this.timeZone);
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
        return new JsonObjectMapper(this.timeZone);
    }

}
