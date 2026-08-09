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
package com.bytequay.app.developmentflow.execution.agentturn;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** Repository- and domain-neutral provider boundary for one admitted Turn. */
public interface AgentTurnProviderSession
{
    /**
     * Builds an inert provider session. Implementations must not start a
     * process, open a network request, or deliver the prompt until
     * {@link Session#startAndAwait(WriterFence)}. This lets the dispatcher
     * install cancellation and, for a code turn, consume a fresh writer
     * authorization immediately around the external launch.
     */
    Session open(Request request, Observer observer)
            throws Exception;

    interface Session
            extends AutoCloseable
    {
        /**
         * Starts and waits for the provider. Read-only turns pass
         * {@code null}; worktree-writing turns must pass the exact live fence
         * supplied by {@code WorktreeWriterLeaseManager}.
         */
        Result startAndAwait(WriterFence writerFence)
                throws Exception;

        void cancel();

        @Override
        void close();
    }

    interface Observer
    {
        void providerSession(String provider, String sessionId);

        void processStarted(long pid, String logReference);

        void log(long sequence, String payloadJson);
    }

    record Request(
            Transport transport,
            String provider,
            String credentialAccount,
            String model,
            String reasoningEffort,
            Path workingDirectory,
            String systemPrompt,
            String prompt,
            List<ImageAttachment> images,
            OwnerToolEndpoint toolEndpoint,
            String permissionPromptTool,
            Access access,
            Long maxCostUsdMilli,
            String resumeSessionId,
            String fallbackPrompt,
            long priorCumulativeInputTokens,
            long priorCumulativeOutputTokens,
            Set<String> preapprovedMcpTools)
    {
        public Request
        {
            requireNonNull(transport, "transport is null");
            requireText(provider, "provider");
            requireText(model, "model");
            requireNonNull(workingDirectory, "workingDirectory is null");
            requireText(prompt, "prompt");
            images = images == null ? List.of() : List.copyOf(images);
            requireNonNull(toolEndpoint, "toolEndpoint is null");
            requireNonNull(access, "access is null");
            preapprovedMcpTools = preapprovedMcpTools == null
                    ? ImmutableSet.of() : ImmutableSet.copyOf(preapprovedMcpTools);
            if (permissionPromptTool != null
                    && !permissionPromptTool.equals(
                            toolEndpoint.approvalPromptTool())) {
                throw new IllegalArgumentException(
                        "permissionPromptTool must name the frozen owner gate");
            }
            if (!workingDirectory.isAbsolute()
                    || !workingDirectory.normalize().equals(workingDirectory)) {
                throw new IllegalArgumentException(
                        "workingDirectory must be an absolute normalized path");
            }
            if (credentialAccount != null && credentialAccount.isBlank()) {
                throw new IllegalArgumentException(
                        "credentialAccount must not be blank");
            }
            if (transport == Transport.CLI && credentialAccount != null) {
                throw new IllegalArgumentException(
                        "CLI provider credentials are managed outside ByteQuay");
            }
            if (reasoningEffort != null && reasoningEffort.isBlank()) {
                throw new IllegalArgumentException("reasoningEffort must not be blank");
            }
            if (systemPrompt != null && systemPrompt.isBlank()) {
                throw new IllegalArgumentException("systemPrompt must not be blank");
            }
            if (maxCostUsdMilli != null && maxCostUsdMilli < 1) {
                throw new IllegalArgumentException(
                        "maxCostUsdMilli must be positive");
            }
            if (resumeSessionId != null && resumeSessionId.isBlank()) {
                throw new IllegalArgumentException(
                        "resumeSessionId must not be blank");
            }
            if (fallbackPrompt != null && fallbackPrompt.isBlank()) {
                throw new IllegalArgumentException(
                        "fallbackPrompt must not be blank");
            }
            if ((resumeSessionId == null) != (fallbackPrompt == null)) {
                throw new IllegalArgumentException(
                        "resumeSessionId and fallbackPrompt must be supplied together");
            }
            if (resumeSessionId != null && transport != Transport.CLI) {
                throw new IllegalArgumentException(
                        "only CLI provider sessions may resume");
            }
            if (priorCumulativeInputTokens < 0
                    || priorCumulativeOutputTokens < 0) {
                throw new IllegalArgumentException(
                        "prior cumulative usage must be non-negative");
            }
            if (resumeSessionId == null
                    && (priorCumulativeInputTokens != 0
                    || priorCumulativeOutputTokens != 0)) {
                throw new IllegalArgumentException(
                        "prior cumulative usage requires a resumed session");
            }
            if (!preapprovedMcpTools.isEmpty()
                    && (access != Access.READ_ONLY
                    || toolEndpoint.profile() != ToolProfile.TASK_BRAIN_READ_ONLY
                    || permissionPromptTool != null)) {
                throw new IllegalArgumentException(
                        "preapproved MCP tools require a finite read-only Task Brain");
            }
            if (preapprovedMcpTools.stream().anyMatch(
                    tool -> tool == null || !tool.matches("[a-z][a-z0-9_]*"))) {
                throw new IllegalArgumentException(
                        "preapproved MCP tools must use bare tool names");
            }
            if ((access == Access.READ_ONLY
                    && toolEndpoint.profile() != ToolProfile.TASK_BRAIN_READ_ONLY
                    && toolEndpoint.profile() != ToolProfile.TRUNK_CONTROL_READ_ONLY
                    && toolEndpoint.profile() != ToolProfile.REVIEW_ASSIGNMENT_READ_ONLY)
                    || (access == Access.WORKTREE_WRITE
                    && toolEndpoint.profile() != ToolProfile.STAGE_DEVELOPMENT)) {
                throw new IllegalArgumentException(
                        "tool profile does not match provider access");
            }
        }

        public Request(
                Transport transport,
                String provider,
                String credentialAccount,
                String model,
                String reasoningEffort,
                Path workingDirectory,
                String systemPrompt,
                String prompt,
                OwnerToolEndpoint toolEndpoint,
                Access access)
        {
            this(transport, provider, credentialAccount, model,
                    reasoningEffort, workingDirectory, systemPrompt, prompt,
                    List.of(), toolEndpoint, toolEndpoint.approvalPromptTool(),
                    access, null, null, null, 0, 0, ImmutableSet.of());
        }

        public Request(
                Transport transport,
                String provider,
                String credentialAccount,
                String model,
                String reasoningEffort,
                Path workingDirectory,
                String systemPrompt,
                String prompt,
                List<ImageAttachment> images,
                OwnerToolEndpoint toolEndpoint,
                Access access)
        {
            this(transport, provider, credentialAccount, model,
                    reasoningEffort, workingDirectory, systemPrompt, prompt,
                    images, toolEndpoint, toolEndpoint.approvalPromptTool(),
                    access, null, null, null, 0, 0, ImmutableSet.of());
        }

        public Request(
                Transport transport,
                String provider,
                String credentialAccount,
                String model,
                String reasoningEffort,
                Path workingDirectory,
                String systemPrompt,
                String prompt,
                List<ImageAttachment> images,
                OwnerToolEndpoint toolEndpoint,
                Access access,
                Long maxCostUsdMilli)
        {
            this(transport, provider, credentialAccount, model,
                    reasoningEffort, workingDirectory, systemPrompt, prompt,
                    images, toolEndpoint, toolEndpoint.approvalPromptTool(),
                    access, maxCostUsdMilli, null, null, 0, 0, ImmutableSet.of());
        }

        public Request(
                Transport transport,
                String provider,
                String credentialAccount,
                String model,
                String reasoningEffort,
                Path workingDirectory,
                String systemPrompt,
                String prompt,
                List<ImageAttachment> images,
                OwnerToolEndpoint toolEndpoint,
                Access access,
                Long maxCostUsdMilli,
                String resumeSessionId,
                String fallbackPrompt)
        {
            this(transport, provider, credentialAccount, model,
                    reasoningEffort, workingDirectory, systemPrompt, prompt,
                    images, toolEndpoint, toolEndpoint.approvalPromptTool(),
                    access, maxCostUsdMilli,
                    resumeSessionId, fallbackPrompt, 0, 0, ImmutableSet.of());
        }

        /** Compatibility constructor for callers whose complete catalog exposes the gate. */
        public Request(
                Transport transport,
                String provider,
                String credentialAccount,
                String model,
                String reasoningEffort,
                Path workingDirectory,
                String systemPrompt,
                String prompt,
                List<ImageAttachment> images,
                OwnerToolEndpoint toolEndpoint,
                Access access,
                Long maxCostUsdMilli,
                String resumeSessionId,
                String fallbackPrompt,
                long priorCumulativeInputTokens,
                long priorCumulativeOutputTokens)
        {
            this(transport, provider, credentialAccount, model,
                    reasoningEffort, workingDirectory, systemPrompt, prompt,
                    images, toolEndpoint, toolEndpoint.approvalPromptTool(),
                    access, maxCostUsdMilli, resumeSessionId, fallbackPrompt,
                    priorCumulativeInputTokens, priorCumulativeOutputTokens,
                    ImmutableSet.of());
        }

        /** Compatibility shape for callers that choose the permission bridge. */
        public Request(
                Transport transport,
                String provider,
                String credentialAccount,
                String model,
                String reasoningEffort,
                Path workingDirectory,
                String systemPrompt,
                String prompt,
                List<ImageAttachment> images,
                OwnerToolEndpoint toolEndpoint,
                String permissionPromptTool,
                Access access,
                Long maxCostUsdMilli,
                String resumeSessionId,
                String fallbackPrompt,
                long priorCumulativeInputTokens,
                long priorCumulativeOutputTokens)
        {
            this(transport, provider, credentialAccount, model,
                    reasoningEffort, workingDirectory, systemPrompt, prompt,
                    images, toolEndpoint, permissionPromptTool, access,
                    maxCostUsdMilli, resumeSessionId, fallbackPrompt,
                    priorCumulativeInputTokens, priorCumulativeOutputTokens,
                    ImmutableSet.of());
        }
    }

    /** Immutable image identity frozen before asynchronous dispatch. */
    record ImageAttachment(String path, String mediaType, String digest)
    {
        public ImageAttachment
        {
            Path image = Path.of(requireText(path, "path"));
            if (!image.isAbsolute() || !image.normalize().equals(image)) {
                throw new IllegalArgumentException(
                        "image path must be absolute and normalized");
            }
            requireText(mediaType, "mediaType");
            requireText(digest, "digest");
            if (!digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "image digest must be a SHA-256 digest");
            }
        }

        public byte[] readVerified()
        {
            byte[] content;
            try {
                content = Files.readAllBytes(Path.of(path));
            }
            catch (IOException e) {
                throw new IllegalStateException(
                        "could not read frozen image " + path, e);
            }
            String actual;
            try {
                actual = HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(content));
            }
            catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is unavailable", e);
            }
            if (!digest.equals(actual)) {
                throw new IllegalStateException(
                        "frozen image content changed before provider launch: " + path);
            }
            return content;
        }
    }

    /**
     * Frozen identity of the one ByteQuay-owned MCP endpoint exposed to this
     * Turn. Provider adapters must ignore personal MCP configuration and must
     * not substitute a broader endpoint.
     */
    record OwnerToolEndpoint(
            String serverName,
            String url,
            DispatchTicket.OwnerKind ownerKind,
            String ownerId,
            String operationId,
            ToolProfile profile,
            String approvalPromptTool)
    {
        public OwnerToolEndpoint
        {
            requireText(serverName, "serverName");
            requireText(url, "url");
            requireNonNull(ownerKind, "ownerKind is null");
            requireText(ownerId, "ownerId");
            requireText(operationId, "operationId");
            requireNonNull(profile, "profile is null");
            if (approvalPromptTool != null) {
                requireText(approvalPromptTool, "approvalPromptTool");
            }
            if (!"bytequay".equals(serverName)) {
                throw new IllegalArgumentException(
                        "only the ByteQuay MCP server is allowed");
            }
            if (!(ownerKind == DispatchTicket.OwnerKind.THREAD_TURN
                    || ownerKind == DispatchTicket.OwnerKind.TASK_TURN
                    || ownerKind == DispatchTicket.OwnerKind.STAGE_TURN
                    || ownerKind == DispatchTicket.OwnerKind.REVIEW_ASSIGNMENT_TURN)) {
                throw new IllegalArgumentException(
                        "Agent Turn MCP endpoint needs a typed Turn owner");
            }
            if (approvalPromptTool != null
                    && !("mcp__" + serverName + "__approval_prompt")
                    .equals(approvalPromptTool)) {
                throw new IllegalArgumentException(
                        "approvalPromptTool must name the scoped ByteQuay gate");
            }
            if (!ownerId.matches("[A-Za-z0-9._:-]+")
                    || !operationId.matches("[A-Za-z0-9._:-]+")) {
                throw new IllegalArgumentException(
                        "tool endpoint identities must be URL-safe");
            }
            URI endpoint = URI.create(url);
            String host = endpoint.getHost();
            if (!"http".equals(endpoint.getScheme())
                    || endpoint.getPort() < 1
                    || !("127.0.0.1".equals(host)
                    || "localhost".equalsIgnoreCase(host)
                    || "::1".equals(host))
                    || endpoint.getUserInfo() != null
                    || endpoint.getFragment() != null
                    || endpoint.getQuery() != null
                    || endpoint.getPath() == null
                    || endpoint.getPath().isBlank()) {
                throw new IllegalArgumentException(
                        "tool endpoint must be an exact loopback HTTP URL");
            }
            String ownerPath = switch (ownerKind) {
                case THREAD_TURN -> "thread-turns";
                case TASK_TURN -> "task-turns";
                case STAGE_TURN -> "stage-turns";
                case REVIEW_ASSIGNMENT_TURN -> "review-assignment-turns";
                default -> throw new IllegalArgumentException(
                        "Agent Turn MCP endpoint needs a typed Turn owner");
            };
            String expectedPath = "/api/v2/" + ownerPath + "/" + ownerId
                    + "/operations/" + operationId + "/mcp";
            if (!expectedPath.equals(endpoint.getPath())) {
                throw new IllegalArgumentException(
                        "tool endpoint URL does not name its exact typed Turn");
            }
        }
    }

    /** Exact, immutable mutation identity visible to a provider adapter. */
    record WriterFence(
            String worktreePath,
            String taskId,
            String operationId,
            long taskEpoch,
            long fencingToken)
    {
        public WriterFence
        {
            requireText(worktreePath, "worktreePath");
            requireText(taskId, "taskId");
            requireText(operationId, "operationId");
            Path path = Path.of(worktreePath);
            if (!path.isAbsolute() || !path.normalize().equals(path)) {
                throw new IllegalArgumentException(
                        "worktreePath must be an absolute normalized path");
            }
            if (taskEpoch < 1 || fencingToken < 1) {
                throw new IllegalArgumentException(
                        "writer epoch and fencing token must be positive");
            }
        }
    }

    record Result(
            Completion completion,
            String providerSessionId,
            String finalText,
            long inputTokens,
            long outputTokens,
            long costUsdMilli,
            Long processPid,
            String error,
            Long cumulativeInputTokens,
            Long cumulativeOutputTokens)
    {
        public Result
        {
            requireNonNull(completion, "completion is null");
            if (providerSessionId != null && providerSessionId.isBlank()) {
                throw new IllegalArgumentException("providerSessionId must not be blank");
            }
            if (finalText == null) {
                finalText = "";
            }
            if (inputTokens < 0 || outputTokens < 0 || costUsdMilli < 0) {
                throw new IllegalArgumentException("usage must be non-negative");
            }
            if ((cumulativeInputTokens == null)
                    != (cumulativeOutputTokens == null)) {
                throw new IllegalArgumentException(
                        "cumulative usage must be supplied together");
            }
            if (cumulativeInputTokens != null
                    && (cumulativeInputTokens < inputTokens
                    || cumulativeOutputTokens < outputTokens)) {
                throw new IllegalArgumentException(
                        "cumulative usage must include this Turn's usage");
            }
            if (processPid != null && processPid < 1) {
                throw new IllegalArgumentException("processPid must be positive");
            }
            if (completion == Completion.SUCCEEDED && error != null) {
                throw new IllegalArgumentException("successful provider result has an error");
            }
            if (completion != Completion.SUCCEEDED
                    && (error == null || error.isBlank())) {
                throw new IllegalArgumentException("unsuccessful provider result needs an error");
            }
        }

        public Result(
                Completion completion,
                String providerSessionId,
                String finalText,
                long inputTokens,
                long outputTokens,
                long costUsdMilli,
                Long processPid,
                String error)
        {
            this(completion, providerSessionId, finalText, inputTokens,
                    outputTokens, costUsdMilli, processPid, error, null, null);
        }
    }

    enum Transport
    {
        CLI,
        API
    }

    enum Access
    {
        READ_ONLY,
        WORKTREE_WRITE
    }

    enum ToolProfile
    {
        TRUNK_CONTROL_READ_ONLY,
        TASK_BRAIN_READ_ONLY,
        REVIEW_ASSIGNMENT_READ_ONLY,
        STAGE_DEVELOPMENT
    }

    enum Completion
    {
        SUCCEEDED,
        FAILED,
        CANCELED
    }

    private static String requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return value;
    }
}
