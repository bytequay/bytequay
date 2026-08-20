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
package com.bytequay.persistence.sqlite.entity;

import com.bytequay.core.cherrypick.CherryPickExecution.ActionJournal;
import com.bytequay.core.cherrypick.CherryPickExecution.ActionType;
import com.bytequay.core.cherrypick.CherryPickItem;
import com.bytequay.core.cherrypick.CherryPickItem.ConflictPath;
import com.bytequay.core.cherrypick.CherryPickItem.ConflictType;
import com.bytequay.core.cherrypick.CherryPickItem.Disposition;
import com.bytequay.core.cherrypick.CherryPickItem.PullRequestRefView;
import com.bytequay.core.cherrypick.CherryPickItem.Result;
import com.bytequay.core.cherrypick.CherryPickStorageException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;

final class CherryPickJson
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<ItemValue>> ITEM_VALUES = new TypeReference<>() {};

    private CherryPickJson() {}

    public static String encodeItems(List<CherryPickItem> items)
    {
        return encode(items.stream().map(CherryPickJson::toValue).collect(toImmutableList()), "cherry-pick items");
    }

    public static List<CherryPickItem> decodeItems(String json)
    {
        if (json == null || json.isBlank()) {
            return ImmutableList.of();
        }
        try {
            return MAPPER.readValue(json, ITEM_VALUES).stream()
                    .map(CherryPickJson::toItem)
                    .collect(toImmutableList());
        }
        catch (JsonProcessingException | RuntimeException e) {
            throw new CherryPickStorageException("Cannot deserialize cherry-pick items", e);
        }
    }

    public static String encodeJournal(Optional<ActionJournal> journal)
    {
        return journal
                .map(entry -> encode(new JournalValue(
                        entry.type().name(),
                        entry.sourceSha().orElse(null),
                        entry.expectedHead(),
                        entry.attempt(),
                        entry.idempotencyKey(),
                        entry.startedAt().toString()), "cherry-pick action journal"))
                .orElse(null);
    }

    public static Optional<ActionJournal> decodeJournal(String json)
    {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JournalValue value = MAPPER.readValue(json, JournalValue.class);
            return Optional.of(new ActionJournal(
                    ActionType.valueOf(value.type()),
                    Optional.ofNullable(value.sourceSha()),
                    value.expectedHead(),
                    value.attempt(),
                    value.idempotencyKey(),
                    Instant.parse(value.startedAt())));
        }
        catch (JsonProcessingException | RuntimeException e) {
            throw new CherryPickStorageException("Cannot deserialize cherry-pick action journal", e);
        }
    }

    private static ItemValue toValue(CherryPickItem item)
    {
        return new ItemValue(
                item.position(),
                item.sourceSha(),
                item.sourceParentSha(),
                item.title(),
                item.pullRequest()
                        .map(pr -> new PullRequestValue(pr.number(), pr.title(), pr.url()))
                        .orElse(null),
                item.disposition().name(),
                item.result().name(),
                item.targetSha().orElse(null),
                item.conflictPaths().stream()
                        .map(path -> new ConflictValue(path.path(), path.type().name(), path.module().orElse(null)))
                        .collect(toImmutableList()),
                item.agentTurnCount());
    }

    private static CherryPickItem toItem(ItemValue value)
    {
        return new CherryPickItem(
                value.position(),
                value.sourceSha(),
                value.sourceParentSha(),
                value.title(),
                Optional.ofNullable(value.pullRequest())
                        .map(pr -> new PullRequestRefView(pr.number(), pr.title(), pr.url())),
                Disposition.valueOf(value.disposition()),
                Result.valueOf(value.result()),
                Optional.ofNullable(value.targetSha()),
                value.conflictPaths() == null
                        ? ImmutableList.of()
                        : value.conflictPaths().stream()
                                .map(path -> new ConflictPath(
                                        path.path(),
                                        ConflictType.valueOf(path.type()),
                                        Optional.ofNullable(path.module())))
                                .collect(toImmutableList()),
                value.agentTurnCount());
    }

    private static String encode(Object value, String description)
    {
        try {
            return MAPPER.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new CherryPickStorageException("Cannot serialize " + description, e);
        }
    }

    private record ItemValue(
            int position,
            String sourceSha,
            String sourceParentSha,
            String title,
            PullRequestValue pullRequest,
            String disposition,
            String result,
            String targetSha,
            List<ConflictValue> conflictPaths,
            int agentTurnCount) {}

    private record PullRequestValue(int number, String title, String url) {}

    private record ConflictValue(String path, String type, String module) {}

    private record JournalValue(
            String type,
            String sourceSha,
            String expectedHead,
            int attempt,
            String idempotencyKey,
            String startedAt) {}
}
