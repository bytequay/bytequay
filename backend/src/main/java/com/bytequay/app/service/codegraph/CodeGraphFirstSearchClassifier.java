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
    private static final Pattern SEARCH_COMMAND = Pattern.compile(
            "(?s)(?:^|[;&|()]|\\s)(?:[^\\s]*/)?(?:rg|grep|egrep|fgrep)\\s+([^;&|]+)");
    private static final Pattern SYMBOL = Pattern.compile("[A-Za-z_$][A-Za-z0-9_.$:-]*");
    private static final Set<String> OPTIONS_WITH_VALUES = Set.of(
            "-g", "--glob", "--iglob", "-t", "--type", "-T", "--type-not",
            "-e", "--regexp", "-f", "--file", "--encoding", "--engine");

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

    /** A model-ready replacement query derived from the rejected native call. */
    public static Suggestion suggestion(ApprovalContext context)
    {
        String tool = context.shortToolName();
        if ("Grep".equals(tool)) {
            String pattern = text(context.toolInput(), "pattern");
            if (isSymbol(pattern)) {
                return new Suggestion(pattern, true);
            }
            return new Suggestion("Find code matching " + quoted(pattern)
                    + scopeSuffix(context.toolInput())
                    + ". Return relevant files, symbols, callers, and tests.", false);
        }
        if ("Glob".equals(tool)) {
            String pattern = text(context.toolInput(), "pattern");
            return new Suggestion("Map source files matching " + quoted(pattern)
                    + " and explain the relevant symbols and call paths.", false);
        }
        String command = context.shellCommand();
        String term = shellSearchTerm(command);
        if (isSymbol(term)) {
            return new Suggestion(term, true);
        }
        return new Suggestion("Map code relevant to this blocked search: "
                + compact(command) + ". Return implementation files, symbols, callers, tests, "
                + "and change impact.", false);
    }

    public record Suggestion(String query, boolean symbol) {}

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

    private static String shellSearchTerm(String command)
    {
        if (command == null) {
            return "";
        }
        var matcher = SEARCH_COMMAND.matcher(command);
        if (!matcher.find()) {
            return "";
        }
        boolean skipValue = false;
        for (String raw : matcher.group(1).strip().split("\\s+")) {
            String token = stripQuotes(raw);
            if (skipValue) {
                skipValue = false;
                continue;
            }
            if (OPTIONS_WITH_VALUES.contains(token)) {
                skipValue = true;
                continue;
            }
            if (token.startsWith("-")) {
                continue;
            }
            return token;
        }
        return "";
    }

    private static String text(JsonNode input, String field)
    {
        if (input == null) {
            return "";
        }
        JsonNode value = input.get(field);
        return value != null && value.isTextual() ? value.asText().strip() : "";
    }

    private static String scopeSuffix(JsonNode input)
    {
        String path = text(input, "path");
        return path.isBlank() ? "" : " under " + quoted(path);
    }

    private static boolean isSymbol(String value)
    {
        return value != null && SYMBOL.matcher(value.strip()).matches();
    }

    private static String quoted(String value)
    {
        return value == null || value.isBlank() ? "the requested pattern" : "'" + compact(value) + "'";
    }

    private static String compact(String value)
    {
        if (value == null || value.isBlank()) {
            return "the current task";
        }
        String compact = value.replaceAll("\\s+", " ").strip();
        return compact.length() <= 300 ? compact : compact.substring(0, 300) + "…";
    }

    private static String stripQuotes(String value)
    {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '\"' && last == '\"')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
