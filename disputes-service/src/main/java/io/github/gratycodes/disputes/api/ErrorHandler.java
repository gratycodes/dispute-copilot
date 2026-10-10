package io.github.gratycodes.disputes.api;

import io.github.gratycodes.disputes.domain.IllegalTransitionException;
import io.github.gratycodes.disputes.service.ConflictException;
import io.github.gratycodes.disputes.service.NotFoundException;
import io.github.gratycodes.disputes.service.UnprocessableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ErrorHandler {

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler(IllegalTransitionException.class)
    ProblemDetail illegal(IllegalTransitionException e) {
        ProblemDetail p = problem(HttpStatus.CONFLICT, "Illegal transition", e.getMessage());
        p.setProperty("status_before", e.from().name());
        p.setProperty("command", e.command().name());
        return p;
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage());
    }

    @ExceptionHandler(UnprocessableException.class)
    ProblemDetail unprocessable(UnprocessableException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Unprocessable", e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(title);
        return p;
    }
}
