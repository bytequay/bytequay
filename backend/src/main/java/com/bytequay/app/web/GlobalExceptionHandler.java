/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bytequay.app.web;

import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.domain.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Set;

@RestControllerAdvice
public class GlobalExceptionHandler
{
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String INTERNAL_SERVER_ERROR_MESSAGE = "Internal server error";

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatusException(ResponseStatusException exception, HttpServletRequest request)
    {
        HttpStatusCode statusCode = exception.getStatusCode();
        if (statusCode.value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            log.warn("Request temporarily unavailable: {} ({})", request.getRequestURI(), exception.getReason());
        }
        else if (statusCode.is5xxServerError()) {
            log.warn("Request failed: {}", request.getRequestURI(), exception);
        }
        String message = exception.getReason() != null ? exception.getReason() : statusCode.toString();
        return errorResponse(statusCode.value(), message, request.getRequestURI());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException exception, HttpServletRequest request)
    {
        return errorResponse(HttpStatus.BAD_REQUEST.value(), exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFoundException(NotFoundException exception, HttpServletRequest request)
    {
        return errorResponse(HttpStatus.NOT_FOUND.value(), exception.getMessage(), request.getRequestURI());
    }

    /**
     * A command did not match the current state of its aggregate — the task is
     * terminal, the stage moved on, the version is stale. That is the caller's
     * answer, not a server fault, so it gets a 409 (404 when the subject is
     * simply gone) carrying the rejection reason instead of the catch-all's
     * opaque 500. The UI shows this text, so keep the message user-readable.
     */
    @ExceptionHandler(CommandRejectedException.class)
    public ResponseEntity<ErrorResponse> handleCommandRejected(
            CommandRejectedException exception, HttpServletRequest request)
    {
        HttpStatus status = exception.reason() == CommandRejectedException.Reason.NOT_FOUND
                ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        log.warn("Command rejected ({}): {} — {}",
                exception.reason(), request.getRequestURI(), exception.getMessage());
        return errorResponse(status.value(), exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingRequestParameter(
            MissingServletRequestParameterException exception, HttpServletRequest request)
    {
        return errorResponse(HttpStatus.BAD_REQUEST.value(), exception.getMessage(), request.getRequestURI());
    }

    /**
     * The client closed the connection before we finished writing the
     * response (a broken pipe — e.g. the MCP CLI subprocess timed out,
     * was interrupted, or the user navigated away mid-request). The
     * socket is already gone, so there's nothing to send and nothing
     * actionable on our side. Log quietly and return {@code void} so the
     * resolver doesn't attempt a second write onto the dead socket (which
     * would just raise the same error again).
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnect(AsyncRequestNotUsableException exception, HttpServletRequest request)
    {
        log.debug("Client disconnected before response was flushed: {} ({})",
                request.getRequestURI(), exception.getMessage());
    }

    /**
     * A request hit a known route with the wrong HTTP verb. The most common
     * source is a Streamable-HTTP MCP client (notably Codex's {@code rmcp}
     * client) opening a {@code GET} on the POST-only MCP endpoint to probe for
     * a server→client SSE stream. The transport spec lets us decline that GET,
     * but it MUST be answered with {@code 405 Method Not Allowed} (plus an
     * {@code Allow} header) — NOT the {@code 500} the catch-all below would
     * otherwise produce. A 500 makes the client treat the whole MCP session as
     * broken and discard the tools it just enumerated via {@code tools/list},
     * which is exactly what stranded Codex on its built-in sub-agent fallback.
     *
     * <p>The response is deliberately body-less. The SSE probe sends
     * {@code Accept: text/event-stream}, which no message converter can satisfy
     * for a JSON {@code ErrorResponse}; returning a body here made the resolver
     * raise {@code HttpMediaTypeNotAcceptableException} ("No acceptable
     * representation") while writing the 405, defeating the whole point of this
     * handler. A 405 carries its meaning in the status line and {@code Allow}
     * header, so we skip the body and skip content negotiation entirely.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Void> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception, HttpServletRequest request)
    {
        log.debug("Method {} not supported for {}", exception.getMethod(), request.getRequestURI());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        Set<HttpMethod> allowed = exception.getSupportedHttpMethods();
        if (allowed != null && !allowed.isEmpty()) {
            builder.allow(allowed.toArray(new HttpMethod[0]));
        }
        return builder.build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception exception, HttpServletRequest request)
    {
        log.error("Unhandled request failure: {}", request.getRequestURI(), exception);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), INTERNAL_SERVER_ERROR_MESSAGE, request.getRequestURI());
    }

    private static ResponseEntity<ErrorResponse> errorResponse(int status, String message, String path)
    {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(Instant.now(), status, message, path));
    }

    public record ErrorResponse(Instant timestamp, int status, String message, String path) {}
}
