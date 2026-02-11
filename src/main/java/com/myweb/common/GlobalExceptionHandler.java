package com.myweb.common;

import com.myweb.exception.AccessDeniedException;
import com.myweb.exception.BadRequestException;
import com.myweb.exception.FileUploadException;
import com.myweb.exception.UnAuthenticatedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局异常处理
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    // 国际化
    private MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }


    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseBody
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResult handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        return ApiResult.failed(HttpStatus.BAD_REQUEST.value(), e.getMessage(), processBindingErrors(e.getBindingResult()));
    }

    @ExceptionHandler(BindException.class)
    @ResponseBody
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResult handleBindException(BindException e) {
        return ApiResult.failed(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", processBindingErrors(e.getBindingResult()));
    }

    @ExceptionHandler(UnAuthenticatedException.class)
    @ResponseBody
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResult handleUnAuthenticatedException(UnAuthenticatedException e) {
        return ApiResult.failed(HttpStatus.UNAUTHORIZED.value(), e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseBody
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResult handleAccessDeniedException(AccessDeniedException e) {
        return ApiResult.failed(HttpStatus.FORBIDDEN.value(), e.getMessage());
    }

    @ExceptionHandler(FileUploadException.class)
    @ResponseBody
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResult handleFileUploadException(FileUploadException e) {
        return ApiResult.failed(HttpStatus.BAD_REQUEST.value(), e.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    @ResponseBody
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResult handleBadRequestException(BadRequestException e) {
        return ApiResult.failed(HttpStatus.BAD_REQUEST.value(), e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseBody
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public ApiResult<String> handleSizeError(MaxUploadSizeExceededException e) {
        return ApiResult.failed(HttpStatus.PAYLOAD_TOO_LARGE.value(),
                String.format("文件太大或请求体超出限制(%d MB)", DataSize.ofBytes(e.getMaxUploadSize()).toMegabytes()));
    }

    @ExceptionHandler(Exception.class)
    public ModelAndView handleException(Exception e, HttpServletRequest request, HttpServletResponse response) {
        return determineView(e, request, response, HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    private boolean isAjaxRequest(HttpServletRequest request) {
        // 方法1：传统方式
        if ("XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
            return true;
        }

        // 方法2：检查Accept头部
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains("application/json")) {
            return true;
        }

        // 方法3：检查Content-Type
        String contentType = request.getHeader("Content-Type");
        if (contentType != null && contentType.contains("application/json")) {
            return true;
        }

        // 方法4：检查Fetch-Site（现代浏览器）
        if ("same-origin".equals(request.getHeader("Sec-Fetch-Site"))
                && "cors".equals(request.getHeader("Sec-Fetch-Mode"))) {
            return true;
        }

        return false;
    }

    private Map<String, String> processBindingErrors(BindingResult bindingResult) {
        if (bindingResult == null) {
            return Collections.emptyMap();
        }

        List<FieldError> fieldErrors = bindingResult.getFieldErrors();
        if (fieldErrors == null || fieldErrors.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> errorMap = new HashMap<>(fieldErrors.size());
        for (FieldError fieldError : fieldErrors) {
            errorMap.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return errorMap;
    }

    private ModelAndView determineView(Exception e, HttpServletRequest request, HttpServletResponse response, int statusCode) {
        if (isAjaxRequest(request)) {
            response.setCharacterEncoding("UTF-8");
            response.setStatus(statusCode);
            return new ModelAndView();
        } else {
            ModelAndView mv = new ModelAndView("error");
            String ctxPath = request.getContextPath();
            if (ctxPath == null || "/".equals(ctxPath)) {
                ctxPath = "";
            }
            mv.addObject("ctxPath", ctxPath);
            mv.addObject("errorCode", statusCode);
            mv.addObject("message", e.getMessage());
            return mv;
        }
    }

}
