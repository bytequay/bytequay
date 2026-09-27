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
package com.bytequay.app.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.net.URI;
import java.net.URLEncoder;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;

/** Jira wire projections. Rich text is rendered as Markdown; credentials never enter this projection. */
final class JiraMapping
{
    private static final Pattern PADDED = Pattern.compile("([\\s\\p{Z}]*)(.*?)([\\s\\p{Z}]*)", Pattern.DOTALL);

    private JiraMapping() {}

    static JiraIssue issue(String connectionId, String cloudId, String siteUrl, JsonNode value)
    {
        return issue(connectionId, cloudId, siteUrl, value, value.path("fields").path("description"));
    }

    static JiraIssue issue(String connectionId, String cloudId, String siteUrl, JsonNode value, JsonNode description)
    {
        JsonNode fields = value.path("fields");
        JsonNode parent = fields.path("parent");
        return new JiraIssue(connectionId, cloudId, value.path("id").asText(), value.path("key").asText(),
                siteUrl + "/browse/" + JiraConnections.segment(value.path("key").asText()), fields.path("summary").asText(),
                markdown(siteUrl, description).strip(), fields.path("issuetype").path("name").asText(), status(fields.path("status")),
                optional(fields.path("assignee")).map(JiraMapping::person), nodes(fields.path("labels")).stream().map(JsonNode::asText).collect(toImmutableList()),
                optional(fields.path("priority")).map(JiraMapping::choice), optional(parent).map(item -> new JiraDtos.Parent(item.path("id").asText(),
                        item.path("key").asText(), item.path("fields").path("summary").asText())),
                !fields.path("subtasks").isEmpty() || fields.path("issuetype").path("hierarchyLevel").asInt() > 0,
                false, instant(fields.path("updated").asText()), text(fields, "duedate"), Optional.empty(), ImmutableList.of(), false, completedAt(fields), Optional.empty());
    }

    private static Optional<Instant> completedAt(JsonNode fields)
    {
        if (!"done".equalsIgnoreCase(fields.path("status").path("statusCategory").path("key").asText())) {
            return Optional.empty();
        }
        return text(fields, "statuscategorychangedate").or(() -> text(fields, "resolutiondate")).map(JiraMapping::instant);
    }

    static JiraDtos.Person person(JsonNode value)
    {
        Optional<String> avatarUrl = ImmutableList.of("48x48", "32x32", "24x24", "16x16").stream()
                .flatMap(size -> text(value.path("avatarUrls"), size).stream()).filter(JiraMapping::isAvatarUrl).findFirst();
        return new JiraDtos.Person(value.path("accountId").asText(), value.path("displayName").asText("Unknown user"), avatarUrl);
    }

    private static boolean isAvatarUrl(String value)
    {
        try {
            URI uri = URI.create(value);
            String host = Optional.ofNullable(uri.getHost()).orElse("").toLowerCase(Locale.ROOT);
            // Images load without Jira credentials; only retain Atlassian's avatar hosts.
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                    && (host.endsWith(".atlassian.net") || host.endsWith(".atl-paas.net") || host.equals("avatar-cdn.atlassian.com")
                    || host.equals("api.atlassian.com") || host.equals("secure.gravatar.com"));
        }
        catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static JiraDtos.Status status(JsonNode value)
    {
        return new JiraDtos.Status(value.path("id").asText(), value.path("name").asText(), value.path("statusCategory").path("key").asText());
    }

    static JiraDtos.Choice choice(JsonNode value)
    {
        return new JiraDtos.Choice(value.path("id").asText(), value.path("name").asText(value.path("value").asText()));
    }

    static List<JiraDtos.Field> fields(JsonNode value)
    {
        ImmutableList.Builder<JiraDtos.Field> fields = ImmutableList.builder();
        value.fields().forEachRemaining(entry -> {
            JsonNode field = entry.getValue();
            fields.add(new JiraDtos.Field(entry.getKey(), field.path("name").asText(entry.getKey()), field.path("required").asBoolean(),
                    field.path("schema").path("type").asText(), nodes(field.path("allowedValues")).stream().map(JiraMapping::choice).collect(toImmutableList())));
        });
        return fields.build();
    }

    static JiraDtos.Comment comment(String siteUrl, JsonNode value)
    {
        return new JiraDtos.Comment(value.path("id").asText(), person(value.path("author")), markdown(siteUrl, value.path("body")).strip(),
                value.path("created").asText(), value.path("updated").asText());
    }

    static Optional<String> text(JsonNode value, String field)
    {
        return optional(value.path(field)).map(JsonNode::asText).filter(item -> !item.isBlank());
    }

    static List<JsonNode> nodes(JsonNode value)
    {
        return value.isArray() ? StreamSupport.stream(value.spliterator(), false).collect(toImmutableList()) : ImmutableList.of();
    }

    private static Optional<JsonNode> optional(JsonNode value)
    {
        return value.isMissingNode() || value.isNull() ? Optional.empty() : Optional.of(value);
    }

    static Instant instant(String value)
    {
        try {
            return Instant.parse(value);
        }
        catch (java.time.format.DateTimeParseException ignored) {
            return OffsetDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")).toInstant();
        }
    }

    static Map<String, Object> document(String value)
    {
        List<Map<String, Object>> content = value.lines().map(line -> ImmutableMap.<String, Object>of("type", "paragraph", "content",
                line.isEmpty() ? ImmutableList.of() : ImmutableList.of(ImmutableMap.of("type", "text", "text", line)))).collect(toImmutableList());
        return ImmutableMap.of("version", 1, "type", "doc", "content", content);
    }

    static String markdown(String siteUrl, JsonNode node)
    {
        if (node.isTextual()) {
            return escape(node.asText());
        }
        return switch (node.path("type").asText()) {
            case "text" -> markedText(node);
            case "paragraph" -> markdownChildren(siteUrl, node).stripTrailing() + "\n\n";
            case "heading" -> "#".repeat(Math.clamp(node.path("attrs").path("level").asInt(1), 1, 6)) + " " + markdownChildren(siteUrl, node).strip() + "\n\n";
            case "blockquote" -> "> " + markdownChildren(siteUrl, node).strip().replace("\n", "\n> ") + "\n\n";
            case "bulletList", "orderedList" -> list(siteUrl, node);
            case "hardBreak" -> "\n";
            case "rule" -> "\n---\n";
            case "codeBlock" -> codeBlock(node);
            case "mention" -> mention(siteUrl, node.path("attrs"));
            case "emoji" -> node.path("attrs").path("shortName").asText();
            case "inlineCard", "blockCard" -> link(escape(node.path("attrs").path("url").asText()), node.path("attrs").path("url").asText());
            case "table" -> table(siteUrl, node);
            case "media", "mediaSingle", "mediaGroup" -> "[Attachment: open in Jira]";
            default -> markdownChildren(siteUrl, node);
        };
    }

    private static String markdownChildren(String siteUrl, JsonNode node)
    {
        return nodes(node.path("content")).stream().map(child -> markdown(siteUrl, child)).collect(joining());
    }

    private static String mention(String siteUrl, JsonNode attrs)
    {
        String name = text(attrs, "text").orElse("@user").strip();
        String label = escape(name.startsWith("@") ? name : "@" + name);
        return text(attrs, "id").map(id -> link(label, siteUrl + "/jira/people/" + URLEncoder.encode(id, UTF_8).replace("+", "%20"))).orElse(label);
    }

    private static String markedText(JsonNode node)
    {
        String value = node.path("text").asText();
        List<JsonNode> marks = nodes(node.path("marks"));
        String text = escape(value);
        if (marks.stream().anyMatch(mark -> mark.path("type").asText().equals("code"))) {
            String fence = backticks(value, 1);
            String padding = value.startsWith("`") || value.endsWith("`") || (value.startsWith(" ") && value.endsWith(" ") && !value.isBlank()) ? " " : "";
            text = fence + padding + value + padding + fence;
        }
        if (marks.stream().anyMatch(mark -> mark.path("type").asText().equals("strong"))) {
            text = emphasis(text, "**");
        }
        if (marks.stream().anyMatch(mark -> mark.path("type").asText().equals("em"))) {
            text = emphasis(text, "_");
        }
        for (JsonNode mark : marks) {
            if (mark.path("type").asText().equals("link")) {
                text = link(text, mark.path("attrs").path("href").asText());
            }
        }
        return text;
    }

    private static String emphasis(String text, String delimiter)
    {
        // A delimiter that touches whitespace is literal in Markdown, so Jira's "italic " becomes "_italic_ ".
        Matcher parts = PADDED.matcher(text);
        return !parts.matches() || parts.group(2).isEmpty() ? text : parts.group(1) + delimiter + parts.group(2) + delimiter + parts.group(3);
    }

    private static String link(String text, String href)
    {
        try {
            URI uri = URI.create(href);
            if (("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null && uri.getUserInfo() == null) {
                return "[" + text + "](" + uri.toASCIIString().replace("(", "%28").replace(")", "%29") + ")";
            }
        }
        catch (IllegalArgumentException ignored) {
            // Malformed links remain readable text.
        }
        return text;
    }

    private static String table(String siteUrl, JsonNode node)
    {
        List<JsonNode> rows = nodes(node.path("content"));
        if (rows.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < rows.size(); index++) {
            List<JsonNode> cells = nodes(rows.get(index).path("content"));
            // ponytail: GFM cells flatten block layout; use a rich-text renderer if merged or multi-block cells need fidelity.
            result.append(cells.stream().map(cell -> markdown(siteUrl, cell))
                    .map(cell -> cell.strip().replaceAll("\\s*\\R\\s*", " ").replace("|", "\\|"))
                    .collect(joining(" | ", "| ", " |\n")));
            if (index == 0) {
                result.append("| ").append("--- | ".repeat(cells.size()).stripTrailing()).append('\n');
            }
        }
        return result + "\n";
    }

    private static String list(String siteUrl, JsonNode node)
    {
        boolean ordered = node.path("type").asText().equals("orderedList");
        int number = Math.max(1, node.path("attrs").path("order").asInt(1));
        StringBuilder result = new StringBuilder();
        for (JsonNode item : nodes(node.path("content"))) {
            String prefix = ordered ? number++ + ". " : "- ";
            result.append(prefix).append(markdown(siteUrl, item).strip().replace("\n", "\n" + " ".repeat(prefix.length()))).append('\n');
        }
        return result + "\n";
    }

    private static String codeBlock(JsonNode node)
    {
        String value = raw(node);
        String fence = backticks(value, 3);
        String language = node.path("attrs").path("language").asText();
        return fence + (language.matches("[\\w+-]+") ? language : "") + "\n" + value + "\n" + fence + "\n\n";
    }

    private static String backticks(String value, int minimum)
    {
        int longest = Pattern.compile("`+").matcher(value).results().mapToInt(match -> match.group().length()).max().orElse(0);
        return "`".repeat(Math.max(minimum, longest + 1));
    }

    private static String raw(JsonNode node)
    {
        return node.path("type").asText().equals("text") ? node.path("text").asText()
                : nodes(node.path("content")).stream().map(JiraMapping::raw).collect(joining());
    }

    private static String escape(String value)
    {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replaceAll("([\\\\`*_\\[\\]#!+().~-])", "\\\\$1");
    }
}
