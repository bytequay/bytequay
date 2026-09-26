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
package com.bytequay.persistence.sqlite.credential;

import com.bytequay.core.store.JiraConnectionStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteJiraConnectionStore
        implements JiraConnectionStore
{
    private final JiraConnectionJpaRepository rows;
    private final CredentialCipher cipher;

    public SqliteJiraConnectionStore(JiraConnectionJpaRepository rows, CredentialCipher cipher)
    {
        this.rows = requireNonNull(rows, "rows is null");
        this.cipher = requireNonNull(cipher, "cipher is null");
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> ids()
    {
        return rows.findAll().stream().map(JiraConnectionEntity::getId).sorted().collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Connection> find(String id)
    {
        return rows.findById(id).map(row -> new Connection(row.getId(), row.getBrokerUrl(), cipher.decrypt(row.getCiphertext()), row.getStateJson()));
    }

    @Override
    @Transactional
    public void save(Connection connection)
    {
        rows.save(new JiraConnectionEntity(connection.id(), connection.authUrl(), cipher.encrypt(connection.credential()), connection.stateJson()));
    }

    @Override
    @Transactional
    public void delete(String id)
    {
        rows.deleteById(id);
    }
}
