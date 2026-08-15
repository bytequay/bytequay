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

import org.springframework.stereotype.Repository;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Repository
public class SqliteAppSettingsStore
{
    public static final String GITHUB_LOGIN = "github.login";

    private final AppSettingJpaRepository jpaRepository;

    public SqliteAppSettingsStore(AppSettingJpaRepository jpaRepository)
    {
        this.jpaRepository = requireNonNull(jpaRepository, "jpaRepository is null");
    }

    public Optional<String> get(String key)
    {
        return jpaRepository.findById(key).map(AppSettingEntity::getValue);
    }

    public void set(String key, String value)
    {
        AppSettingEntity entity = jpaRepository.findById(key)
                .orElseGet(() -> new AppSettingEntity(key, value));
        entity.setValue(value);
        jpaRepository.save(entity);
    }
}
