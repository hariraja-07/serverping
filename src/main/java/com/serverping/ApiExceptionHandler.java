package com.serverping;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.serverping.target.InvalidTargetException;
import com.serverping.target.TargetNotFoundException;

/**
 * Turns every failure into RFC 9457 {@code application/problem+json} instead of ad-hoc
 * {@code {"error": ...}} blobs, so clients can parse errors the same way everywhere.
 *
 * <p>Status codes follow one rule: <em>400 = the request was malformed or mis-typed,
 * 404 = well-formed but no such thing, 422 = well-formed and understood, but semantically
 * refused by our own rules.</em> That is why a blank name is 400 while a private IP is 422.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(TargetNotFoundException.class)
    public ProblemDetail targetNotFound(TargetNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Target Not Found", ex.getMessage());
    }

    /** The SSRF guard firing: syntactically valid, deliberately refused. */
    @ExceptionHandler(InvalidTargetException.class)
    public ProblemDetail invalidTarget(InvalidTargetException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid Target", ex.getMessage());
    }

    /** Bean validation failed — carry the per-field messages so the client can highlight them. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validationFailed(MethodArgumentNotValidException ex) {
        ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, "Validation Failed",
                "One or more fields are invalid.");

        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        detail.setProperty("errors", errors);
        return detail;
    }

    /** Body was not JSON, or contained an unparseable value such as an unknown protocol. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail unreadable(HttpMessageNotReadableException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Malformed Request",
                "The request body could not be parsed as JSON, or contains an unknown value.");
    }

    /** A path or query parameter had the wrong type, e.g. {@code /targets/abc}. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail typeMismatch(MethodArgumentTypeMismatchException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid Parameter",
                "Parameter '" + ex.getName() + "' has an invalid value: '"
                        + ex.getValue() + "'.");
    }

    /** No handler and no static resource matched — Spring raises this instead of a bare 404. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail noHandler(NoResourceFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Not Found",
                "No endpoint exists at the requested path.");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(title);
        problem.setDetail(detail);
        return problem;
    }
}
