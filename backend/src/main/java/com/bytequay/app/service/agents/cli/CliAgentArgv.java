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
package com.bytequay.app.service.agents.cli;

import com.google.common.collect.ImmutableList;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * The command line that launches a coding-agent CLI, for Claude Code and Codex.
 *
 * <p>Flow-neutral on purpose. These flags are the part that is genuinely
 * expensive to rediscover — each one is a vendor quirk someone learned from a
 * failed run — while everything around them (who authorized the turn, how its
 * death is proven, which tools it may call) differs between the flows that use
 * it. Two copies of this list would drift, and the drift would show up as an
 * agent that silently inherited a personal MCP catalog or a missing budget cap.
 *
 * <p>Deliberately takes plain values rather than any flow's request type. The
 * caller resolves policy — read-only versus writing, which tools are allowed,
 * what the budget is — and this only spells it. That is what lets the greenfield
 * runtime share it without naming an old-flow tool profile or dispatch owner.
 */
public final class CliAgentArgv
{
    /**
     * Turns off memory and commit attribution. A background agent writing to
     * someone's memory file, or stamping its own name on their commits, is a
     * surprise the user never asked for.
     */
    private static final String CLAUDE_ISOLATED_SETTINGS =
            "{\"autoMemoryEnabled\":false,\"attribution\":{\"commit\":\"\"}}";
    /** The built-in tools a read-only turn keeps; everything else is denied. */
    private static final String CLAUDE_READ_ONLY_TOOLS =
            "Read,Glob,Grep,WebFetch,WebSearch";

    private CliAgentArgv() {}

    public enum Vendor
    {
        CLAUDE_CODE,
        CODEX
    }

    /**
     * One CLI launch, already resolved to values.
     *
     * @param readOnly whether the agent may write to the worktree; it selects
     *         Claude's tool allowlist and Codex's sandbox mode, and is the one
     *         parameter whose wrong value silently grants write access
     * @param mcpConfig Claude's MCP config file; required for Claude, unused by
     *         Codex, which takes its server inline
     * @param mcpUrl the loopback tool endpoint Codex connects to; required for
     *         Codex, unused by Claude
     * @param allowedTools fully-qualified tool rules to allow, already expanded
     *         by the caller. Empty means the vendor default for {@code readOnly}
     * @param maxCostUsdMilli the turn's own budget cap in thousandths of a
     *         dollar, or null for none
     * @param resumeSessionId a prior provider session to continue, or null for a
     *         fresh one
     */
    public record Launch(
            Vendor vendor,
            String executable,
            String model,
            String reasoningEffort,
            Path workingDirectory,
            String systemPrompt,
            boolean readOnly,
            Path mcpConfig,
            String mcpUrl,
            String permissionPromptTool,
            Long maxCostUsdMilli,
            String resumeSessionId,
            List<String> allowedTools,
            List<String> imagePaths,
            boolean mcpOnly)
    {
        public Launch(
                Vendor vendor,
                String executable,
                String model,
                String reasoningEffort,
                Path workingDirectory,
                String systemPrompt,
                boolean readOnly,
                Path mcpConfig,
                String mcpUrl,
                String permissionPromptTool,
                Long maxCostUsdMilli,
                String resumeSessionId,
                List<String> allowedTools,
                List<String> imagePaths)
        {
            this(vendor, executable, model, reasoningEffort, workingDirectory,
                    systemPrompt, readOnly, mcpConfig, mcpUrl,
                    permissionPromptTool, maxCostUsdMilli, resumeSessionId,
                    allowedTools, imagePaths, false);
        }

        public Launch
        {
            requireNonNull(vendor, "vendor is null");
            requireText(executable, "executable");
            requireText(model, "model");
            requireNonNull(workingDirectory, "workingDirectory is null");
            allowedTools = allowedTools == null
                    ? List.of() : List.copyOf(allowedTools);
            imagePaths = imagePaths == null
                    ? List.of() : List.copyOf(imagePaths);
            if (maxCostUsdMilli != null && maxCostUsdMilli < 1) {
                throw new IllegalArgumentException(
                        "maxCostUsdMilli must be positive");
            }
            switch (vendor) {
                case CLAUDE_CODE -> requireNonNull(
                        mcpConfig, "Claude MCP config is null");
                case CODEX -> requireText(mcpUrl, "mcpUrl");
            }
        }
    }

    public static List<String> of(Launch launch)
    {
        requireNonNull(launch, "launch is null");
        return launch.vendor() == Vendor.CLAUDE_CODE
                ? claude(launch) : codex(launch);
    }

    private static List<String> claude(Launch launch)
    {
        ImmutableList.Builder<String> argv = ImmutableList.<String>builder()
                .add(launch.executable())
                .add("-p")
                .add("--output-format", "stream-json")
                .add("--verbose")
                .add("--setting-sources", "")
                .add("--disable-slash-commands")
                .add("--no-chrome")
                .add("--settings", CLAUDE_ISOLATED_SETTINGS)
                .add("--include-partial-messages")
                .add("--mcp-config", launch.mcpConfig().toString())
                .add("--strict-mcp-config");
        if (launch.permissionPromptTool() != null) {
            argv.add("--permission-prompt-tool", launch.permissionPromptTool());
        }
        else {
            // Print-mode agents have nobody at a terminal to approve a tool.
            // Explicitly preapproved tools still run; every other permission
            // is denied instead of hanging on a prompt that cannot be answered.
            argv.add("--permission-mode", "dontAsk");
        }
        argv.add("--model", launch.model());
        if (launch.reasoningEffort() != null) {
            argv.add("--effort", launch.reasoningEffort());
        }
        if (launch.maxCostUsdMilli() != null) {
            argv.add("--max-budget-usd", BigDecimal
                    .valueOf(launch.maxCostUsdMilli(), 3)
                    .stripTrailingZeros()
                    .toPlainString());
        }
        if (launch.mcpOnly()) {
            argv.add("--tools", "");
        }
        else if (launch.readOnly()) {
            argv.add("--tools", CLAUDE_READ_ONLY_TOOLS);
        }
        ImmutableList.Builder<String> allowedTools = ImmutableList.builder();
        allowedTools.addAll(launch.allowedTools());
        if (launch.systemPrompt() != null) {
            argv.add("--append-system-prompt", launch.systemPrompt());
        }
        if (launch.resumeSessionId() != null) {
            argv.add("--resume", launch.resumeSessionId());
        }
        launch.imagePaths().stream()
                .map(Path::of)
                .map(Path::getParent)
                .distinct()
                .forEach(directory -> argv.add(
                        "--add-dir", directory.toString()));
        if (!launch.imagePaths().isEmpty()) {
            // Granting the directory is not enough on its own; each attachment
            // needs its own absolute read rule.
            allowedTools.addAll(launch.imagePaths().stream()
                    .map(CliAgentArgv::absoluteReadRule)
                    .toList());
        }
        List<String> allowed = allowedTools.build();
        if (!allowed.isEmpty()) {
            // Claude documents this as one comma-or-space separated option.
            // One flag avoids vendor parsers treating repeated occurrences as
            // replacement and silently retaining only the final MCP tool.
            argv.add("--allowedTools", String.join(",", allowed));
        }
        return argv.build();
    }

    /** Claude permission patterns use a double slash for an absolute path. */
    private static String absoluteReadRule(String image)
    {
        return "Read(/" + Path.of(image).toAbsolutePath().normalize() + ")";
    }

    private static List<String> codex(Launch launch)
    {
        ImmutableList.Builder<String> argv = ImmutableList.<String>builder()
                .add(launch.executable())
                // Replace the complete MCP table. Combined with
                // --ignore-user-config this exposes one owner-scoped server,
                // never an inherited personal MCP catalog.
                .add("-c", "mcp_servers={bytequay={url=\"" + launch.mcpUrl()
                        + "\",default_tools_approval_mode=\"approve\"}}")
                .add("-c", "experimental_use_rmcp_client=true")
                .add("-c", "project_doc_max_bytes=0");
        if (launch.reasoningEffort() != null) {
            argv.add("-c", "model_reasoning_effort=\""
                    + launch.reasoningEffort() + "\"");
        }
        argv.add("exec")
                .add("--ignore-user-config");
        if (launch.resumeSessionId() != null) {
            // The recorded session already owns its cwd and sandbox. Codex
            // rejects those first-turn flags after the resume subcommand.
            argv.add("resume")
                    .add("--json")
                    .add("--skip-git-repo-check")
                    .add("-m", launch.model());
        }
        else {
            argv.add("--json")
                    .add("--skip-git-repo-check")
                    .add("--sandbox", launch.readOnly() || launch.mcpOnly()
                            ? "read-only" : "workspace-write")
                    .add("-C", launch.workingDirectory().toString())
                    .add("-m", launch.model());
        }
        launch.imagePaths().forEach(image -> argv.add("-i", image));
        if (launch.resumeSessionId() != null) {
            argv.add(launch.resumeSessionId());
        }
        // Keep the prompt out of argv. A reconstructed context can be large, and
        // an expired-session restart must not fail at exec(2)'s argument-size
        // limit before the provider can read it.
        argv.add("-");
        return argv.build();
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
