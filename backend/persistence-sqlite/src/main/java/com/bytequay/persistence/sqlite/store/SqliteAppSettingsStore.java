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

import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.persistence.sqlite.entity.AppSettingEntity;
import com.bytequay.persistence.sqlite.repository.AppSettingJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteAppSettingsStore
        implements AppSettingsStore
{
    private final AppSettingJpaRepository repository;

    public SqliteAppSettingsStore(AppSettingJpaRepository repository)
    {
        this.repository = requireNonNull(repository, "repository is null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> get(String key)
    {
        return repository.findById(requireKey(key)).map(AppSettingEntity::getValue);
    }

    @Override
    @Transactional
    public void put(String key, String value)
    {
        requireNonNull(value, "value is null");
        repository.save(new AppSettingEntity(requireKey(key), value));
    }

    @Override
    @Transactional
    public void delete(String key)
    {
        repository.deleteById(requireKey(key));
    }

    private static String requireKey(String key)
    {
        requireNonNull(key, "key is null");
        checkArgument(!key.isBlank(), "key is blank");
        return key;
    }
}
