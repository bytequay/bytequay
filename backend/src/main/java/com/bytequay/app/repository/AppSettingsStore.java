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
package com.bytequay.app.repository;

import java.util.Optional;

/**
 * Persistent key-value store for application configuration.
 * Backed by the {@code app_settings} table.
 *
 * <p>Keys are stable strings defined by {@code AppSettingsStore.Key}.
 */
public interface AppSettingsStore
{
    /** Well-known setting keys. */
    final class Key
    {
        private Key() {}

        public static final String SYNC_INTERVAL_SECONDS = "sync.interval.seconds";
        public static final String GITHUB_PAT = "github.pat";
        public static final String PR_SORT_ORDER = "pr.sort.order";
        public static final String GITHUB_LOGIN = "github.login";
        /** Active LLM provider id: "claude", "openai", or "local". */
        public static final String LLM_PROVIDER = "llm.provider";
        /** Model name for the active provider (e.g. "claude-opus-4-7"). */
        public static final String LLM_MODEL = "llm.model";
        /** Opt-in toggle for the {@code ScheduledReviewService}.
         *  Stored as the string {@code "true"} when enabled; anything
         *  else (or missing) means disabled. Off by default so the
         *  app never silently burns LLM budget on a fresh install. */
        public static final String SCHEDULED_REVIEWS_ENABLED = "scheduled_reviews.enabled";
    }

    /**
     * Returns the value for {@code key}, or empty if the key does not exist.
     */
    Optional<String> get(String key);

    /**
     * Inserts or updates {@code key} with {@code value}.
     */
    void set(String key, String value);
}
