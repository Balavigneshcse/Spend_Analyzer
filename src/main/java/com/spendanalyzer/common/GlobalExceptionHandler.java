package com.spendanalyzer.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.spendanalyzer.security.SameOriginApiFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Turns every exception into the same small JSON body. Never leaks stack traces or internals. */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ErrorBody> handleApi(ApiException ex) {
		ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.status());
		if (ex.retryAfterSeconds() != null) {
			builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()));
		}
		return builder.body(new ErrorBody(ex.code(), ex.getMessage(), ex.fields(), ex.retryAfterSeconds()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorBody> handleInvalid(MethodArgumentNotValidException ex) {
		Map<String, String> fields = new LinkedHashMap<>();
		for (FieldError error : ex.getBindingResult().getFieldErrors()) {
			fields.putIfAbsent(error.getField(),
					error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage());
		}
		String message = fields.isEmpty() ? "Please check your input." : fields.values().iterator().next();
		return ResponseEntity.badRequest().body(new ErrorBody("validation", message, fields, null));
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class })
	public ResponseEntity<ErrorBody> handleUnreadable(Exception ex, HttpServletRequest request) {
		if (bodyTooLarge(request)) {
			return tooLarge();
		}
		return ResponseEntity.badRequest().body(ErrorBody.of("bad_request", "The request could not be read."));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorBody> handleOther(Exception ex, HttpServletRequest request) {
		if (bodyTooLarge(request)) {
			return tooLarge();
		}
		if (ex instanceof ErrorResponse standard) {
			// Spring's own errors (404 for unknown paths, 405, 415 ...) keep their status.
			HttpStatusCode status = standard.getStatusCode();
			String code = status.value() == 404 ? "not_found" : "request_error";
			String message = status.value() == 404 ? "Not found." : "The request could not be handled.";
			return ResponseEntity.status(status).body(ErrorBody.of(code, message));
		}
		String reference = UUID.randomUUID().toString().substring(0, 8);
		log.error("Unhandled error [{}]", reference, ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(ErrorBody.of("server_error", "Something went wrong. Please try again. (ref " + reference + ")"));
	}

	/**
	 * True when reading the body was cut off at the size cap (a body sent without a Content-Length;
	 * one with a length is refused by the filter itself). Whatever exception that surfaced as, the
	 * cause is the size.
	 */
	private static boolean bodyTooLarge(HttpServletRequest request) {
		return request.getAttribute(SameOriginApiFilter.BODY_TOO_LARGE_ATTRIBUTE) != null;
	}

	private static ResponseEntity<ErrorBody> tooLarge() {
		return ResponseEntity.status(413).body(ErrorBody.of("too_large", "That request is too large."));
	}

}
