package cn.culturehub;

import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ErrorAdvice {
  @ExceptionHandler(Problem.class)
  ResponseEntity<?> problem(Problem e) {
    return ResponseEntity.status(e.status)
        .body(
            Map.of(
                "code",
                e.code,
                "message",
                e.getMessage(),
                "serverTime",
                java.time.Instant.now().toString()));
  }

  @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
  ResponseEntity<?> duplicate(Exception e) {
    return ResponseEntity.status(409)
        .body(Map.of("code", "DUPLICATE", "message", "Record already exists"));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    java.time.format.DateTimeParseException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.bind.MethodArgumentNotValidException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    return ResponseEntity.badRequest()
        .body(Map.of("code", "VALIDATION_FAILED", "message", "Invalid input"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception e) {
    String trace = UUID.randomUUID().toString();
    org.slf4j.LoggerFactory.getLogger(getClass()).error("request {}", trace, e);
    return ResponseEntity.internalServerError()
        .body(Map.of("code", "INTERNAL_ERROR", "message", "Request failed", "traceId", trace));
  }
}
