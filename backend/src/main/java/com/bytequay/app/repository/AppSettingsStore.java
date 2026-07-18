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

        /** Maintainer-only opt-in for polling and triaging new issues in the
         * canonical ByteQuay repository. Off by default on every install. */
        public static final String BYTEQUAY_ISSUE_MONITOR_ENABLED = "bytequay.issue_monitor.enabled";

        /** Highest canonical ByteQuay issue number considered by the monitor. */
        public static final String BYTEQUAY_ISSUE_MONITOR_CURSOR = "bytequay.issue_monitor.cursor";

        // Workspace Settings → Behavior toggles. Persistence only —
        // enforcement (actually archiving idle threads, auto-proposing
        // tasks, promoting decisions, nudging new topics) lands as
        // each feature wires through to its consumer. Values stored
        // as strings; sentinel "off" represents the "Never" arm of
        // the archive cadence.
        public static final String BEHAVIOR_ARCHIVE_IDLE_AFTER = "behavior.archive_idle_after";
        public static final String BEHAVIOR_AUTO_PROPOSE_TASK = "behavior.auto_propose_task";
        public static final String BEHAVIOR_AUTO_PROMOTE_DECISIONS = "behavior.auto_promote_decisions";
        public static final String BEHAVIOR_NEW_TOPIC_NUDGE = "behavior.new_topic_nudge";

        /** Memory-axis opt-in: when {@code "true"}, FOCUS_SHIFT
         *  proposals from the workspace distill auto-apply rather
         *  than waiting in the banner. Off by default — the spec
         *  only allows FOCUS_SHIFT (the most volatile / least
         *  load-bearing kind) to skip confirmation; DECISION /
         *  CONVENTION / BLOCKER / OPEN_QUESTION / RECURRING_PATTERN
         *  always propose-then-confirm. */
        public static final String BEHAVIOR_AUTO_APPLY_FOCUS_SHIFT = "behavior.auto_apply_focus_shift";

        // Phase 8 inner-5: a user-editable persona nudge prepended
        // to every panel reviewer's skill-context payload at request
        // time. Empty / missing means no nudge — reviewers run
        // against the bare repo-skill context only.
        public static final String REVIEW_PERSONA = "review.persona";

        // ds4 local-inference server. Every key is read at startup
        // and again whenever the lifecycle service is asked to spawn
        // so apply-on-restart matches what the UI just wrote.
        public static final String DS4_BINARY_PATH = "ds4.binary_path";
        public static final String DS4_PORT = "ds4.port";
        public static final String DS4_MODEL = "ds4.model";
        public static final String DS4_QUANT = "ds4.quant";
        public static final String DS4_CONTEXT_TOKENS = "ds4.context_tokens";
        public static final String DS4_KV_CACHE_DIR = "ds4.kv_cache_dir";
        public static final String DS4_KV_DISK_BUDGET_MB = "ds4.kv_disk_budget_mb";
        public static final String DS4_THINKING_DEFAULT = "ds4.thinking_default";
        public static final String DS4_TRACE = "ds4.trace";
        public static final String DS4_INSTALL_URL = "ds4.install_url";
        public static final String DS4_REPO_DIR = "ds4.repo_dir";
        public static final String DS4_MODEL_VARIANT = "ds4.model_variant";
        public static final String DS4_AUTO_RESTART_ON_CRASH = "ds4.auto_restart_on_crash";
        public static final String DS4_AUTO_START_ON_BOOT = "ds4.auto_start_on_boot";
        public static final String DS4_ATTACH_IF_RUNNING = "ds4.attach_if_running";
        public static final String DS4_ENABLED = "ds4.enabled";
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
