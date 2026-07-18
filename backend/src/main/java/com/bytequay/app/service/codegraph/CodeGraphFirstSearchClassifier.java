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
package com.bytequay.app.service.codegraph;

import com.bytequay.app.service.mcp.approval.ApprovalContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Heuristic classifier for broad Claude CLI Grep/Glob/Bash discovery. */
public final class CodeGraphFirstSearchClassifier
{
    private CodeGraphFirstSearchClassifier() {}

    private static final Set<String> GUIDANCE_FILES = Set.of("AGENTS.md", "CLAUDE.md");
    private static final Pattern RG = command("rg");
    private static final Pattern RECURSIVE_GREP = Pattern.compile(
            "(?s)(?:^|[;&|()]|\\s)(?:[^\\s]*/)?(?:grep|egrep|fgrep)\\s+[^;&|]*(?:-[^\\s]*[rR]|--recursive)");
    private static final Pattern GIT_GREP = Pattern.compile(
            "(?s)(?:^|[;&|()]|\\s)(?:[^\\s]*/)?git\\s+grep(?:\\s|$)");
    private static final Pattern FIND_OR_FD = Pattern.compile(
            "(?s)(?:^|[;&|()]|\\s)(?:[^\\s]*/)?(?:find|fd|fdfind|tree)(?:\\s|$)");
    private static final Pattern EXPLICIT_FILE = Pattern.compile(
            "(?:^|\\s)(?:['\"])?[^\\s'\"*?\\[\\]]+\\.[A-Za-z0-9]{1,12}(?:['\"])?(?:\\s|$)");

    /** True when a Claude approval call should be redirected to CodeGraph. */
    public static boolean isBroadDiscovery(ApprovalContext context)
    {
        String tool = context.shortToolName();
        if ("Grep".equals(tool)) {
            return !hasExplicitFilePath(context.toolInput());
        }
        if ("Glob".equals(tool)) {
            return !isGuidanceGlob(context.toolInput());
        }
        return context.isShellTool() && isBroadShellDiscovery(context.shellCommand());
    }

    static boolean isBroadShellDiscovery(String command)
    {
        if (command == null || command.isBlank()) {
            return false;
        }
        String normalized = command.strip();
        boolean fixed = hasFixedStringFlag(normalized);
        boolean explicitFile = EXPLICIT_FILE.matcher(normalized).find();

        if (RG.matcher(normalized).find()) {
            if (fixed || explicitFile) {
                return false;
            }
            if (normalized.contains("--files") && namesGuidanceFile(normalized)) {
                return false;
            }
            return true;
        }
        if (RECURSIVE_GREP.matcher(normalized).find() || GIT_GREP.matcher(normalized).find()) {
            return !fixed && !explicitFile;
        }
        if (FIND_OR_FD.matcher(normalized).find()) {
            return !namesGuidanceFile(normalized);
        }
        return false;
    }

    private static Pattern command(String name)
    {
        return Pattern.compile("(?s)(?:^|[;&|()]|\\s)(?:[^\\s]*/)?" + name + "(?:\\s|$)");
    }

    private static boolean hasFixedStringFlag(String command)
    {
        return Pattern.compile("(?:^|\\s)(?:-F|--fixed-strings)(?:\\s|$)")
                .matcher(command)
                .find();
    }

    private static boolean namesGuidanceFile(String value)
    {
        String upper = value.toUpperCase(Locale.ROOT);
        return upper.contains("AGENTS.MD") || upper.contains("CLAUDE.MD");
    }

    private static boolean hasExplicitFilePath(JsonNode input)
    {
        if (input == null) {
            return false;
        }
        JsonNode path = input.get("path");
        return path != null && path.isTextual() && looksLikeFile(path.asText());
    }

    private static boolean isGuidanceGlob(JsonNode input)
    {
        if (input == null) {
            return false;
        }
        JsonNode pattern = input.get("pattern");
        if (pattern == null || !pattern.isTextual()) {
            return false;
        }
        String value = pattern.asText();
        return GUIDANCE_FILES.stream().anyMatch(value::endsWith);
    }

    private static boolean looksLikeFile(String path)
    {
        String name = path.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        String basename = slash >= 0 ? name.substring(slash + 1) : name;
        return basename.contains(".") && !basename.endsWith(".");
    }
}
