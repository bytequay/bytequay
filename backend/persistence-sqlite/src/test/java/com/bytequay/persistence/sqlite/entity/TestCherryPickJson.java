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

import com.bytequay.core.cherrypick.CherryPickItem;
import com.bytequay.core.cherrypick.CherryPickItem.ConflictPath;
import com.bytequay.core.cherrypick.CherryPickItem.ConflictType;
import com.bytequay.core.cherrypick.CherryPickItem.Disposition;
import com.bytequay.core.cherrypick.CherryPickItem.IndexStage;
import com.bytequay.core.cherrypick.CherryPickItem.PullRequestRefView;
import com.bytequay.core.store.StorageException;
import com.bytequay.persistence.sqlite.converter.JsonColumns;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCherryPickJson
{
    private static final TypeReference<List<CherryPickItem>> ITEMS = new TypeReference<>() {};

    @Test
    void preservesStoredShapeAndReadsMissingConflictDetails()
            throws Exception
    {
        String sha = "a".repeat(40);
        String parent = "b".repeat(40);
        CherryPickItem item = CherryPickItem.planned(1, sha, parent, "Change", Optional.of(new PullRequestRefView(7, "Pull", "https://github.com/a/b/pull/7")),
                Disposition.TO_CHERRY_PICK).withConflict(ImmutableList.of(new ConflictPath("file", ConflictType.CONTENT, Optional.empty(),
                        ImmutableList.of(new IndexStage(1, "100644", parent)))));
        String stored = """
                [{"position":1,"sourceSha":"%s","sourceParentSha":"%s","title":"Change",
                  "pullRequest":{"number":7,"title":"Pull","url":"https://github.com/a/b/pull/7"},
                  "disposition":"TO_CHERRY_PICK","result":"PENDING","targetSha":null,"agentTurnCount":0,
                  "conflictPaths":[{"path":"file","type":"CONTENT","module":null,"stages":[{"stage":1,"mode":"100644","blobSha":"%s"}]}]}]
                """.formatted(sha, parent, parent);
        ObjectMapper mapper = JsonColumns.mapper();
        assertThat(mapper.readTree(JsonGuard.CHERRY_PICK.write(ImmutableList.of(item), "cherry-pick items"))).isEqualTo(mapper.readTree(stored));
        assertThat(JsonGuard.CHERRY_PICK.read(stored, ITEMS, "cherry-pick items")).containsExactly(item);
        String noStages = stored.replace("[{\"stage\":1,\"mode\":\"100644\",\"blobSha\":\"" + parent + "\"}]", "null");
        assertThat(JsonGuard.CHERRY_PICK.read(noStages, ITEMS, "cherry-pick items").getFirst().conflictPaths().getFirst().stages()).isEmpty();
        String noConflicts = stored.substring(0, stored.indexOf("\"conflictPaths\"")) + "\"conflictPaths\":null}]";
        assertThat(JsonGuard.CHERRY_PICK.read(noConflicts, ITEMS, "cherry-pick items").getFirst().conflictPaths()).isEmpty();
        assertThatThrownBy(() -> JsonGuard.CHERRY_PICK.read(stored.replace("\"stage\":1", "\"stage\":0"), ITEMS, "cherry-pick items")).isInstanceOf(StorageException.class);
    }
}
