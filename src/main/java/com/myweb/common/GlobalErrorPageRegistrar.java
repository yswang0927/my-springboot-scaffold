package com.myweb.common;

import org.springframework.boot.web.server.ErrorPage;
import org.springframework.boot.web.server.ErrorPageRegistrar;
import org.springframework.boot.web.server.ErrorPageRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 注册错误页面，替代springboot内置的/error页面。
 */
@Component
public class GlobalErrorPageRegistrar implements ErrorPageRegistrar {
    @Override
    public void registerErrorPages(ErrorPageRegistry registry) {
        // 1. 为 404 状态码注册自定义错误页面
        ErrorPage error404Page = new ErrorPage(HttpStatus.NOT_FOUND, "/common/error-404");
        // 2. 为 500 状态码注册自定义错误页面
        ErrorPage error500Page = new ErrorPage(HttpStatus.INTERNAL_SERVER_ERROR, "/common/error-500");
        // 3. 为特定异常（如空指针）注册错误页面
        ErrorPage npePage = new ErrorPage(NullPointerException.class, "/common/error");

        registry.addErrorPages(error404Page);
        registry.addErrorPages(error500Page);
        registry.addErrorPages(npePage);
    }
}
