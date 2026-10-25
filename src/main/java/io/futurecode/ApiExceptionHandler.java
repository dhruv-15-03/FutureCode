package io.futurecode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import tools.jackson.databind.node.ObjectNode;

@RestControllerAdvice
public final class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(ApiError.class) public ResponseEntity<ObjectNode> expected(ApiError error) {
        return ResponseEntity.status(error.status()).body(Json.obj("error", error.getMessage(), "code", error.code()));
    }
    @ExceptionHandler({NoHandlerFoundException.class, HttpRequestMethodNotSupportedException.class})
    public ResponseEntity<ObjectNode> missing() { return expected(ApiError.missing()); }
    @ExceptionHandler(Exception.class) public ResponseEntity<ObjectNode> unexpected(Exception error) {
        LOG.error("Request failed ({}). No request content or credentials were logged.", error.getClass().getSimpleName());
        return ResponseEntity.status(500).body(Json.obj("error", "Server error. Retry later; no success was confirmed.", "code", "server_error"));
    }
}
