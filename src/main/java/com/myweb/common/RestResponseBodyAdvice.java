package com.myweb.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 统一封装 REST-Controller 返回结果
 */
@RestControllerAdvice
public class RestResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    private final ObjectMapper objectMapper;

    public RestResponseBodyAdvice(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return !returnType.getContainingClass().isAnnotationPresent(IgnoreRestBody.class)
                && !returnType.hasMethodAnnotation(IgnoreRestBody.class);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        /*
         * 这里需要对返回的 String 类型进行特殊处理，否则会报：
         * ClassCastException: xxxx cannot be cast to java.lang.String
         * 原因：Spring会根据请求头的Accept来选择对应的转换器，而 String 类型的转换器是放在最后来的
         */
        if (returnType.getGenericParameterType().equals(String.class)) {
            try {
                response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                return objectMapper.writeValueAsString(ApiResult.success(body));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        if (body instanceof ApiResult apiResult) {
            if (apiResult.isErrorResult()) {
                final int errorCode = apiResult.getCode();
                if (errorCode >= 100 && errorCode <= 999) {
                    response.setStatusCode(HttpStatusCode.valueOf(errorCode));
                }
            }
            return body;
        }

        return ApiResult.success(body);
    }

}
