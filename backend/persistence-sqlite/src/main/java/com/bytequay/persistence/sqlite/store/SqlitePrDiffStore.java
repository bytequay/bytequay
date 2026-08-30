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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.PrDiffEtags;
import com.bytequay.core.domain.StoredPrDiff;
import com.bytequay.core.store.PrDiffStore;
import com.bytequay.persistence.sqlite.entity.PrDiffEntity;
import com.bytequay.persistence.sqlite.repository.PrDiffJpaRepository;
import com.google.common.collect.ImmutableMap;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

@Repository
public class SqlitePrDiffStore
        implements PrDiffStore
{
    private final PrDiffJpaRepository repository;

    public SqlitePrDiffStore(PrDiffJpaRepository repository)
    {
        this.repository = requireNonNull(repository, "repository is null");
    }

    @Override
    public Optional<StoredPrDiff> find(long prId, String baseSha, String headSha)
    {
        return repository.findById(snapshotId(prId, baseSha, headSha))
                .map(entity -> new StoredPrDiff(
                        entity.getFiles(), entity.getCommits(), entity.getFileContents()));
    }

    @Override
    @Transactional
    public void save(long prId, String baseSha, String headSha, StoredPrDiff diff)
    {
        save(prId, baseSha, headSha, diff, PrDiffEtags.none());
    }

    @Override
    public PrDiffEtags findDefaultEtags(long prId)
    {
        return repository.findById(snapshotId(prId, null, null))
                .map(entity -> new PrDiffEtags(
                        Optional.ofNullable(entity.getFilesEtag()),
                        Optional.ofNullable(entity.getCommitsEtag())))
                .orElseGet(PrDiffEtags::none);
    }

    @Override
    @Transactional
    public void saveDefault(long prId, StoredPrDiff diff, PrDiffEtags etags)
    {
        save(prId, null, null, diff, etags);
    }

    private void save(long prId, String baseSha, String headSha, StoredPrDiff diff, PrDiffEtags etags)
    {
        requireNonNull(diff, "diff is null");
        requireNonNull(etags, "etags is null");
        PrDiffEntity entity = new PrDiffEntity();
        entity.setId(snapshotId(prId, baseSha, headSha));
        entity.setPrId(prId);
        entity.setBaseSha(baseSha);
        entity.setHeadSha(headSha);
        entity.setFiles(diff.files());
        entity.setCommits(diff.commits());
        entity.setFileContents(diff.fileContents());
        entity.setSyncedAt(Instant.now());
        entity.setFilesEtag(etags.files().orElse(null));
        entity.setCommitsEtag(etags.commits().orElse(null));
        repository.save(entity);
    }

    @Override
    @Transactional
    public void saveFileContent(
            long prId,
            String baseSha,
            String headSha,
            String path,
            DiffFileContent content)
    {
        requireNonNull(path, "path is null");
        requireNonNull(content, "content is null");
        PrDiffEntity entity = repository.findById(snapshotId(prId, baseSha, headSha))
                .orElseThrow(() -> new IllegalStateException("Pull request diff snapshot is not stored"));
        HashMap<String, DiffFileContent> contents = new HashMap<>(entity.getFileContents());
        contents.put(path, content);
        entity.setFileContents(ImmutableMap.copyOf(contents));
        entity.setSyncedAt(Instant.now());
    }

    @Override
    @Transactional
    public void deleteByPrIds(Set<Long> prIds)
    {
        if (!prIds.isEmpty()) {
            repository.deleteByPrIdIn(prIds);
        }
    }

    private static String snapshotId(long prId, String baseSha, String headSha)
    {
        if ((baseSha == null) != (headSha == null)) {
            throw new IllegalArgumentException("baseSha and headSha must be provided together");
        }
        return baseSha == null ? prId + ":default" : prId + ":" + baseSha + ":" + headSha;
    }
}
