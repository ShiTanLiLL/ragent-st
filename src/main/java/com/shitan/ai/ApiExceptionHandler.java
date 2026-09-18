package com.shitan.ai;

import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

/**
 * 把当前已经出现的参数校验异常转换成稳定、易懂的 HTTP 错误响应。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * 读取第一个字段校验错误，返回 HTTP 400 和简短 JSON；不会调用 RAG 或百炼。
     *
     * @param exception Spring 在处理 @Valid 请求体时产生的校验异常
     * @return 状态码为 400、正文包含具体错误原因的响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        FieldError firstError = exception.getBindingResult().getFieldError();
        String message = firstError == null
                ? "请求参数不合法"
                : firstError.getDefaultMessage();

        return ResponseEntity.badRequest().body(new ApiError(message));
    }

    /**
     * 把不存在的知识库或文档转换成 HTTP 404，让调用方区分“编号不存在”和“服务故障”。
     *
     * @param exception 知识管理服务提供的明确不存在原因
     * @return 状态码为 404、正文包含具体原因的响应
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> handleNotFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(exception.getMessage()));
    }
}
