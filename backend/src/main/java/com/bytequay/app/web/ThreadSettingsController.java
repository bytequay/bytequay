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

import com.bytequay.app.domain.ThreadSettings;
import com.bytequay.app.service.threads.ThreadSettingsService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

/**
 * Per-thread scope settings (caps, prompt addendum). Lives at the
 * thread URL because settings inherit through the scope hierarchy
 * and the resolved view is bound to the thread, not the workspace.
 */
@RestController
@RequestMapping("/api/threads/{threadId}/settings")
public class ThreadSettingsController
{
    private final ThreadSettingsService service;

    public ThreadSettingsController(ThreadSettingsService service)
    {
        this.service = requireNonNull(service, "service is null");
    }

    /** Effective config — global merged with the thread's overrides.
     *  Always returns a payload, even for zero-config threads. */
    @GetMapping
    public Payload get(@PathVariable String threadId)
    {
        ThreadSettingsService.EffectiveSettings effective = service.effective(threadId);
        return new Payload(
                threadId,
                effective.maxRunningTasks(),
                effective.softCostUsdMilli(),
                effective.hardCostUsdMilli(),
                effective.promptAddendum(),
                service.findOverrides(threadId)
                        .map(ThreadSettings::updatedAt)
                        .map(Instant::toString)
                        .orElse(null));
    }

    /** Upsert this thread's overrides. {@code null} fields clear the
     *  override and revert to inheritance. */
    @PutMapping
    public Payload put(@PathVariable String threadId, @RequestBody PatchBody body)
    {
        requireNonNull(body, "body is required");
        ThreadSettings written = service.save(threadId, new ThreadSettings(
                threadId,
                body.maxRunningTasks(),
                body.softCostUsdMilli(),
                body.hardCostUsdMilli(),
                body.promptAddendum(),
                Instant.now()));
        ThreadSettingsService.EffectiveSettings effective = service.effective(threadId);
        return new Payload(
                threadId,
                effective.maxRunningTasks(),
                effective.softCostUsdMilli(),
                effective.hardCostUsdMilli(),
                effective.promptAddendum(),
                written.updatedAt().toString());
    }

    /** Drop all overrides — the thread reverts to silent inheritance. */
    @DeleteMapping
    public void delete(@PathVariable String threadId)
    {
        service.clear(threadId);
    }

    /** Resolved + audit shape. {@code overriddenAt} is the timestamp
     *  on the thread_settings row when present; null on zero-config. */
    public record Payload(
            String threadId,
            int maxRunningTasks,
            int softCostUsdMilli,
            int hardCostUsdMilli,
            String promptAddendum,
            String overriddenAt)
    {
    }

    /** PUT body — every field nullable. {@code null} clears the
     *  override; non-null tightens or relaxes (within the workspace /
     *  global ceiling). */
    public record PatchBody(
            Integer maxRunningTasks,
            Integer softCostUsdMilli,
            Integer hardCostUsdMilli,
            String promptAddendum)
    {
    }
}
