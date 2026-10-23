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
package com.bytequay.app.discussion;

import com.bytequay.agent.spi.DiscussionContextBridge;
import com.bytequay.core.discussion.DiscussionMessage;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.RawValue;
import com.google.common.base.Suppliers;

import java.util.List;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;

/** The session owns these references; no caller-supplied task, discussion identity or path is accepted. */
final class SavedDiscussionContext
{
    static final int PAGE_CHARACTERS = 12_000;

    private SavedDiscussionContext() {}

    static DiscussionContextBridge bridge(Supplier<String> history, String source, List<DiscussionMessage> earlier)
    {
        Supplier<String> savedHistory = Suppliers.memoize(history::get);
        ArrayNode messages = JsonNodeFactory.instance.arrayNode();
        earlier.forEach(message -> messages.add(JsonNodeFactory.instance.objectNode()
                .put("author", message.author().name()).put("body", message.body())));
        String discussion = messages.toString();
        return new DiscussionContextBridge(query -> page(query, switch (query.section()) {
            case "source" -> source;
            case "history" -> savedHistory.get();
            case "discussion" -> discussion;
            default -> throw new IllegalArgumentException("Unknown discussion section");
        }));
    }

    static String comments(List<String> snapshots)
    {
        ArrayNode items = JsonNodeFactory.instance.arrayNode();
        for (int index = 0; index < snapshots.size(); index++) {
            items.add(JsonNodeFactory.instance.objectNode().put("ref", "c" + (index + 1))
                    .putRawValue("comment", new RawValue(snapshots.get(index))));
        }
        return JsonNodeFactory.instance.objectNode().set("items", items).toString();
    }

    private static String page(DiscussionContextBridge.Query query, String text)
    {
        int start = query.offset();
        checkArgument(start <= text.length(), "offset exceeds this section's length");
        checkArgument(start == 0 || start == text.length() || !Character.isLowSurrogate(text.charAt(start)), "offset splits a character");
        int end = Math.min(text.length(), start + PAGE_CHARACTERS);
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        ObjectNode page = JsonNodeFactory.instance.objectNode().put("section", query.section()).put("referenceOnly", true)
                .put("offset", start).put("totalCharacters", text.length()).put("text", text.substring(start, end));
        if (end < text.length()) {
            page.put("nextOffset", end);
        }
        else {
            page.putNull("nextOffset");
        }
        return page.toString();
    }
}
