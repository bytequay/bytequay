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

import com.bytequay.app.domain.RepoIssue;
import com.bytequay.app.scheduler.ByteQuayIssueMonitor;
import com.bytequay.app.service.ByteQuayIssueService;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static com.bytequay.app.utils.StringInputUtil.requireNotBlank;
import static com.bytequay.app.web.RequestValidation.requireBody;
import static java.util.Objects.requireNonNull;

/** Public product-feedback surface plus maintainer-only monitor controls. */
@RestController
@RequestMapping("/api/product-issues")
public class ProductIssueController
{
    private static final int MAX_TITLE_LENGTH = 256;
    private static final int MAX_BODY_LENGTH = 65_536;

    private final ByteQuayIssueService issues;
    private final ByteQuayIssueMonitor monitor;

    public ProductIssueController(ByteQuayIssueService issues, ByteQuayIssueMonitor monitor)
    {
        this.issues = requireNonNull(issues, "issues is null");
        this.monitor = requireNonNull(monitor, "monitor is null");
    }

    @PostMapping
    public RepoIssue report(@RequestBody ReportIssueRequest request)
    {
        request = requireBody(request);
        requireNotBlank(request.title(), "title is required");
        requireNotBlank(request.body(), "body is required");
        if (request.title().strip().length() > MAX_TITLE_LENGTH) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "title is too long");
        }
        if (request.body().strip().length() > MAX_BODY_LENGTH) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "body is too long");
        }
        return issues.report(request.title().strip(), request.body().strip());
    }

    @GetMapping("/monitor")
    public ByteQuayIssueMonitor.MonitorStatus monitorStatus()
    {
        return monitor.status();
    }

    @PutMapping("/monitor")
    public ByteQuayIssueMonitor.MonitorStatus setMonitor(@RequestBody MonitorRequest request)
    {
        request = requireBody(request);
        return monitor.setEnabled(request.enabled());
    }

    public record ReportIssueRequest(String title, String body) {}

    public record MonitorRequest(boolean enabled) {}
}
