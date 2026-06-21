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
package com.bytequay.app.repository.github;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the most recent GitHub REST rate-limit snapshot, updated from the
 * {@code X-RateLimit-*} response headers by {@link GitHubRateLimitInterceptor}
 * on every call. In-memory only: the UI shows the *current* quota, which the
 * next API call refreshes — no per-call DB write, and a restart simply starts
 * empty until the first call lands. Wired as a {@code @Bean} in
 * {@code WebConfig} alongside the GitHub REST client it observes.
 */
public class GitHubRateLimitMonitor
{
    /** A captured rate-limit reading. */
    public record Snapshot(int remaining, int limit, Instant resetAt, Instant recordedAt) {}

    private final AtomicReference<Snapshot> latest = new AtomicReference<>(null);

    public void record(int remaining, int limit, Instant resetAt)
    {
        latest.set(new Snapshot(remaining, limit, resetAt, Instant.now()));
    }

    /** The latest reading, or empty if no GitHub call has landed yet. */
    public Optional<Snapshot> latest()
    {
        return Optional.ofNullable(latest.get());
    }
}
