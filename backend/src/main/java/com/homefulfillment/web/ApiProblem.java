package com.homefulfillment.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.IOException;
import java.util.Map;

public class ApiProblem extends RuntimeException {
    private final int status;
    private final String code;

    public ApiProblem(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiProblem invalid(String message) { return new ApiProblem(400, "INVALID_INPUT", message); }
    public static ApiProblem forbidden() { return new ApiProblem(403, "FORBIDDEN", "没有此角色或仓库权限"); }
    public static ApiProblem missing(String message) { return new ApiProblem(404, "NOT_FOUND", message); }
    public static ApiProblem conflict(String code, String message) { return new ApiProblem(409, code, message); }

    private record Body(String code, String message, String requestId, Map<String, Object> details) {}

    private Body body(HttpServletRequest request) {
        return new Body(code, getMessage(), RequestId.value(request), Map.of());
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ObjectMapper json) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getOutputStream(), body(request));
    }

    @RestControllerAdvice
    static class Handler {
        @ExceptionHandler(ApiProblem.class)
        ResponseEntity<Body> business(ApiProblem problem, HttpServletRequest request) {
            return ResponseEntity.status(problem.status).body(problem.body(request));
        }

        @ExceptionHandler({ServletRequestBindingException.class, HttpMessageNotReadableException.class,
                MethodArgumentTypeMismatchException.class,
                org.springframework.web.bind.MethodArgumentNotValidException.class})
        ResponseEntity<Body> invalid(Exception error, HttpServletRequest request) {
            return business(ApiProblem.invalid("请求参数不合法；数量须为 1–1,000,000 的整数"), request);
        }

        @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
        ResponseEntity<Body> missing(HttpServletRequest request) {
            return business(ApiProblem.missing("资源不存在"), request);
        }

        @ExceptionHandler(RuntimeException.class)
        ResponseEntity<Body> unexpected(RuntimeException error, HttpServletRequest request) {
            LoggerFactory.getLogger(Handler.class).error("requestId={} unexpected error", RequestId.value(request), error);
            return business(new ApiProblem(500, "INTERNAL_ERROR", "操作未确认，请按请求编号查日志"), request);
        }
    }
}
