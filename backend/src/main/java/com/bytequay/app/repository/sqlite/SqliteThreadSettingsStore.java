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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.ThreadSettings;
import com.bytequay.app.repository.ThreadSettingsStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Component
class SqliteThreadSettingsStore
        implements ThreadSettingsStore
{
    private final ThreadSettingsJpaRepository repo;

    SqliteThreadSettingsStore(ThreadSettingsJpaRepository repo)
    {
        this.repo = requireNonNull(repo, "repo is null");
    }

    @Override
    public Optional<ThreadSettings> find(String threadId)
    {
        return repo.findById(threadId).map(SqliteThreadSettingsStore::toDomain);
    }

    @Override
    @Transactional
    public void save(ThreadSettings settings)
    {
        ThreadSettingsEntity e = repo.findById(settings.threadId()).orElseGet(ThreadSettingsEntity::new);
        e.setThreadId(settings.threadId());
        e.setMaxRunningTasks(settings.maxRunningTasks());
        e.setSoftCostUsdMilli(settings.softCostUsdMilli());
        e.setHardCostUsdMilli(settings.hardCostUsdMilli());
        e.setPromptAddendum(settings.promptAddendum());
        e.setUpdatedAtMs(settings.updatedAt().toEpochMilli());
        repo.save(e);
    }

    @Override
    @Transactional
    public void clear(String threadId)
    {
        repo.deleteById(threadId);
    }

    private static ThreadSettings toDomain(ThreadSettingsEntity e)
    {
        return new ThreadSettings(
                e.getThreadId(),
                e.getMaxRunningTasks(),
                e.getSoftCostUsdMilli(),
                e.getHardCostUsdMilli(),
                e.getPromptAddendum(),
                Instant.ofEpochMilli(e.getUpdatedAtMs()));
    }
}
