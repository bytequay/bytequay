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
package com.bytequay.app.web.hosting;

import com.bytequay.core.hosting.HostingFailure;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {HostingController.class, WorkspaceHostingController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HostingExceptionHandler
{
    @ExceptionHandler(HostingFailure.class)
    public ProblemDetail failure(HostingFailure failure)
    {
        HttpStatus status = switch (failure.kind()) {
            case AUTHENTICATION_REQUIRED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case MISSING_OR_INACCESSIBLE -> HttpStatus.NOT_FOUND;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case REVISION_CONFLICT -> HttpStatus.CONFLICT;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INVALID_RESPONSE -> HttpStatus.BAD_GATEWAY;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, failure.getMessage());
        problem.setProperty("kind", failure.kind());
        failure.retryAfter().ifPresent(duration -> problem.setProperty("retryAfterSeconds", duration.toSeconds()));
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalidRequest(IllegalArgumentException failure)
    {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid hosting connection or resource coordinates");
    }
}
