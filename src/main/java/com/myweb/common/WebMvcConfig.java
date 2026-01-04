package com.myweb.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
         registry.addMapping("/api/**")
                 .allowedOriginPatterns("*")
                 .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD")
                 .allowedHeaders("*")
                 .allowCredentials(true)
                 .maxAge(3600);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 如果前端页面是 SPA 单页路由应用方式，则可以针对性的配置前端url地址映射
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
        registry.addResourceHandler("/static/**", "/assets/**")
                .addResourceLocations("classpath:/static/", "classpath:/assets/");
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

}
