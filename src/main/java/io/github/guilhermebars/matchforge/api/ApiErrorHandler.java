package io.github.guilhermebars.matchforge.api;

import io.github.guilhermebars.matchforge.service.EngineService;
import java.net.URI;
import java.util.concurrent.CompletionException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiErrorHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> business(ApiException e) {
        var problem = problem(e.status, e.code);
        if (e.order != null) problem.setProperty("order", e.order);
        return ResponseEntity.status(e.status)
                .header("Idempotent-Replay", Boolean.toString(e.replay))
                .body(problem);
    }

    @ExceptionHandler({IllegalArgumentException.class, ArithmeticException.class})
    public ResponseEntity<Object> invalid(RuntimeException e) {
        return business(new ApiException(400, "VALIDATION_ERROR"));
    }

    @ExceptionHandler(EngineService.UnavailableException.class)
    public ResponseEntity<Object> unavailable(RuntimeException e) {
        return business(new ApiException(503, "ENGINE_OVERLOADED"));
    }

    @ExceptionHandler(CompletionException.class)
    public ResponseEntity<Object> async(CompletionException e) {
        if (e.getCause() instanceof EngineService.UnavailableException u) return unavailable(u);
        return business(new ApiException(503, "ENGINE_UNAVAILABLE"));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return new ResponseEntity<>(
                problem(status.value(), status.value() == 404 ? "NOT_FOUND" : "VALIDATION_ERROR"), headers, status);
    }

    private ProblemDetail problem(int status, String code) {
        var p = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), code.replace('_', ' '));
        p.setType(URI.create("urn:matchforge:problem:" + code.toLowerCase(java.util.Locale.ROOT)));
        p.setProperty("code", code);
        return p;
    }
}
