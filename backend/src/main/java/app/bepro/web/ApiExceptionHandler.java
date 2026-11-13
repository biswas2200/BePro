package app.bepro.web;

import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolationException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ProblemDetail invalidBody(MethodArgumentNotValidException ex) {
		String detail = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.limit(10)
			.map(e -> e.getField() + ": " + e.getDefaultMessage())
			.collect(Collectors.joining("; "));
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
	}

	@ExceptionHandler({ ConstraintViolationException.class, HandlerMethodValidationException.class })
	ProblemDetail invalidParams(Exception ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

}
