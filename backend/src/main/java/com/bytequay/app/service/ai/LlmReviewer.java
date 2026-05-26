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
package com.bytequay.app.service.ai;

import com.bytequay.app.domain.PullRequestDraft;
import com.bytequay.app.domain.ReviewOutput;
import com.bytequay.app.domain.ReviewRequest;
import com.bytequay.app.service.skills.SkillDraft;

import java.util.function.Consumer;

/**
 * Provider-agnostic interface for producing an AI-drafted PR review.
 * Implementations: {@code ClaudeReviewer} (Anthropic), {@code OpenAIReviewer},
 * {@code LocalLlmReviewer} (OpenAI-compatible local servers, e.g. Ollama).
 *
 * <p>Each implementation is responsible for:
 * <ul>
 *   <li>Looking up its own API key / endpoint from {@code CredentialService}</li>
 *   <li>Enforcing reasonable input size (truncating or failing if diff is too large)</li>
 *   <li>Parsing the model's JSON output into {@link ReviewOutput}</li>
 * </ul>
 *
 * <p>First-cut implementations are non-streaming — they return a complete
 * result after the model finishes. A streaming variant can layer on later.
 */
public interface LlmReviewer
{
    /**
     * A stable identifier used in {@code app_settings.llm.provider}.
     * Examples: "claude", "openai", "local".
     */
    String providerId();

    /**
     * Human-readable display name for the provider, e.g. "Claude (Anthropic)".
     */
    String displayName();

    /**
     * Whether this provider is currently configured (e.g. API key is present).
     * The UI uses this to disable provider options that aren't ready to use.
     */
    boolean isConfigured();

    /**
     * Runs a single review call against the provider and returns the full result.
     * Throws on any error — the caller is expected to translate it for the HTTP layer.
     */
    ReviewOutput review(ReviewRequest request);

    /**
     * Streaming variant: forwards raw text deltas as they arrive from the
     * model, then returns the same final {@link ReviewOutput} that
     * {@link #review} would have produced. Default implementation runs the
     * non-streaming path and emits the parsed JSON in one chunk so callers
     * always have a working stream regardless of which provider is active.
     */
    default ReviewOutput reviewStream(ReviewRequest request, Consumer<String> textChunk)
    {
        return review(request);
    }

    /**
     * Rewrites a developer-authored review comment to be clearer, more
     * specific, and friendlier — same thread as the user pasting their
     * draft into ChatGPT and asking "give me better words". Returns the
     * polished text directly (no JSON wrapper, no preamble).
     *
     * <p>Default implementation throws so callers can detect providers
     * that haven't implemented this yet.
     */
    default String polishCommentText(String draft)
    {
        throw new UnsupportedOperationException(
                providerId() + " doesn't support comment polishing yet. Switch to a provider that does in Settings → AI.");
    }

    /**
     * Reads a CI check-run failure (the check name plus whatever log /
     * error context the caller has) and returns a short diagnosis +
     * suggested fix as plain markdown. Used by the merge bar's
     * "Ask AI to fix" button on a failing check card. Default
     * implementation throws so callers can detect providers that
     * haven't implemented this yet.
     */
    default String diagnoseCheckRunFailure(String checkName, String log)
    {
        throw new UnsupportedOperationException(
                providerId() + " doesn't support CI failure diagnosis yet. Switch to a provider that does in Settings → AI.");
    }

    /**
     * Drafts a pull-request title + description from the diff between
     * the branch the user is about to push and the target base. The
     * caller has already gathered the inputs:
     *
     * <ul>
     *   <li>{@code headBranch} — the source branch (current HEAD or
     *       whatever the user picked).</li>
     *   <li>{@code baseBranch} — the merge target (e.g. "main").</li>
     *   <li>{@code diff} — unified diff already truncated to a token-
     *       safe size by the caller.</li>
     *   <li>{@code prTemplate} — the repo's {@code PULL_REQUEST_TEMPLATE.md}
     *       content if found, or null. Templates are rendered into
     *       the description so the team's conventions show through.</li>
     * </ul>
     *
     * <p>Default implementation throws so callers can detect providers
     * that haven't implemented this yet.
     */
    default PullRequestDraft draftPullRequest(
            String headBranch, String baseBranch, String diff, String prTemplate)
    {
        throw new UnsupportedOperationException(
                providerId() + " doesn't support PR drafting yet. Switch to a provider that does in Settings → AI.");
    }

    /**
     * Drafts a library skill from a short user prompt — the
     * Skills modal's "Draft with AI" path. Returns a structured
     * {@link SkillDraft} (name + description + body) for the user
     * to review + edit before saving; the propose-then-confirm
     * pattern keeps an AI mistake from silently landing in the
     * vault.
     *
     * @param userPrompt the user's description of the skill ("describe
     *                   the skill, or paste instructions you repeat")
     * @param scope      one of "global" / "repo" / "role" — feeds the
     *                   prompt so the description is framed for the
     *                   right trigger surface
     *
     * <p>Default implementation throws so callers can detect providers
     * that haven't implemented this yet.
     */
    default SkillDraft draftSkill(String userPrompt, String scope)
    {
        throw new UnsupportedOperationException(
                providerId() + " doesn't support skill drafting yet. Switch to a provider that does in Settings → AI.");
    }
}
