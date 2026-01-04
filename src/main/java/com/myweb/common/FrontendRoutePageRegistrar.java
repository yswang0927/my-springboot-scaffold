package com.myweb.common;

import org.springframework.boot.web.server.ErrorPage;
import org.springframework.boot.web.server.ErrorPageRegistrar;
import org.springframework.boot.web.server.ErrorPageRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 如果前端页面是 SPA 单页路由应用方式，则需要配置 404 页面跳转至 index.html
 */
@Component
public class FrontendRoutePageRegistrar implements ErrorPageRegistrar {
    @Override
    public void registerErrorPages(ErrorPageRegistry registry) {
        // 当Spring找不到对应的Controller处理请求时（返回404）
        // 自动重定向到 /index.html, 前端框架接管路由处理
        ErrorPage frontendIndexPage = new ErrorPage(HttpStatus.NOT_FOUND, "/index");
        registry.addErrorPages(frontendIndexPage);
    }
}
