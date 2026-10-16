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
import com.bytequay.core.cherrypick.CherryPickItem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static com.bytequay.persistence.sqlite.entity.JsonGuard.CHERRY_PICK;

final class CherryPickJson
{
    private static final TypeReference<List<CherryPickItem>> ITEMS = new TypeReference<>() {};

    private CherryPickJson() {}

    public static String encodeItems(List<CherryPickItem> items)
    {
        return CHERRY_PICK.write(items, "cherry-pick items");
    }

    public static List<CherryPickItem> decodeItems(String json)
    {
        return json == null || json.isBlank() ? ImmutableList.of() : ImmutableList.copyOf(CHERRY_PICK.read(json, ITEMS, "cherry-pick items"));
    }

    public static String encodeJournal(Optional<ActionJournal> journal)
    {
        return journal.map(entry -> CHERRY_PICK.write(entry, "cherry-pick action journal")).orElse(null);
    }

    public static Optional<ActionJournal> decodeJournal(String json)
    {
        return json == null || json.isBlank() ? Optional.empty() : Optional.of(CHERRY_PICK.read(json, ActionJournal.class, "cherry-pick action journal"));
    }
}
