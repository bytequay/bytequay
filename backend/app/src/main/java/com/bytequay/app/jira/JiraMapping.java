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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

import static com.google.common.collect.ImmutableList.toImmutableList;

/** Jira wire projections. Rich text is rendered as Markdown; credentials never enter this projection. */
final class JiraMapping
{
    private JiraMapping() {}

    static JiraIssue issue(String connectionId, String cloudId, String siteUrl, JsonNode value)
    {
        JsonNode fields = value.path("fields");
        JsonNode parent = fields.path("parent");
        return new JiraIssue(connectionId, cloudId, value.path("id").asText(), value.path("key").asText(),
                siteUrl + "/browse/" + JiraConnections.segment(value.path("key").asText()), fields.path("summary").asText(),
                markdown(fields.path("description")).strip(), fields.path("issuetype").path("name").asText(), status(fields.path("status")),
                optional(fields.path("assignee")).map(JiraMapping::person), nodes(fields.path("labels")).stream().map(JsonNode::asText).collect(toImmutableList()),
                optional(fields.path("priority")).map(JiraMapping::choice), optional(parent).map(item -> new JiraDtos.Parent(item.path("id").asText(),
                        item.path("key").asText(), item.path("fields").path("summary").asText())),
                !fields.path("subtasks").isEmpty() || fields.path("issuetype").path("hierarchyLevel").asInt() > 0,
                false, instant(fields.path("updated").asText()), text(fields, "duedate"), Optional.empty(), ImmutableList.of(), false);
    }

    static JiraDtos.Person person(JsonNode value)
    {
        // Avoid loading tenant-hosted avatars outside the authenticated transport.
        return new JiraDtos.Person(value.path("accountId").asText(), value.path("displayName").asText("Unknown user"), Optional.empty());
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

    static JiraDtos.Comment comment(JsonNode value)
    {
        return new JiraDtos.Comment(value.path("id").asText(), person(value.path("author")), markdown(value.path("body")).strip(),
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

    static String markdown(JsonNode node)
    {
        if (node.isTextual()) {
            return escape(node.asText());
        }
        String children = nodes(node.path("content")).stream().map(JiraMapping::markdown).collect(java.util.stream.Collectors.joining());
        return switch (node.path("type").asText()) {
            case "text" -> markedText(node);
            case "paragraph", "heading", "blockquote", "tableRow" -> children.stripTrailing() + "\n\n";
            case "bulletList", "orderedList", "doc" -> children;
            case "listItem" -> "- " + children.strip().replace("\n", "\n  ") + "\n";
            case "hardBreak" -> "\n";
            case "rule" -> "\n---\n";
            case "codeBlock" -> "\n" + raw(node).lines().map(line -> "    " + line).collect(java.util.stream.Collectors.joining("\n")) + "\n\n";
            case "mention" -> escape(node.path("attrs").path("text").asText("@user"));
            case "emoji" -> node.path("attrs").path("shortName").asText();
            case "inlineCard", "blockCard" -> escape(node.path("attrs").path("url").asText());
            case "tableCell", "tableHeader" -> children.strip() + " | ";
            case "media", "mediaSingle", "mediaGroup" -> "[Attachment: open in Jira]";
            default -> children;
        };
    }

    private static String markedText(JsonNode node)
    {
        String text = escape(node.path("text").asText());
        for (JsonNode mark : nodes(node.path("marks"))) {
            if (mark.path("type").asText().equals("link")) {
                String href = mark.path("attrs").path("href").asText();
                try {
                    java.net.URI uri = java.net.URI.create(href);
                    if (("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null && uri.getUserInfo() == null) {
                        text = "[" + text.replace("]", "\\]") + "](" + uri.toASCIIString().replace("(", "%28").replace(")", "%29") + ")";
                    }
                }
                catch (IllegalArgumentException ignored) {
                    // Malformed links remain readable text.
                }
            }
        }
        return text;
    }

    private static String raw(JsonNode node)
    {
        return node.path("type").asText().equals("text") ? node.path("text").asText()
                : nodes(node.path("content")).stream().map(JiraMapping::raw).collect(java.util.stream.Collectors.joining());
    }

    private static String escape(String value)
    {
        return value.replace("\\", "\\\\").replace("<", "&lt;").replace(">", "&gt;")
                .replace("*", "\\*").replace("_", "\\_").replace("`", "\\`").replace("[", "\\[");
    }
}
