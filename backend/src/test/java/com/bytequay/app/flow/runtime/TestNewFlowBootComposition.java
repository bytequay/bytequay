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
package com.bytequay.app.flow.runtime;

import com.bytequay.app.flow.github.GitHubCiObservationDispatcher;
import com.bytequay.app.flow.github.GitHubCiUpdateDispatcher;
import com.bytequay.app.flow.github.GitHubInitialPublishDispatcher;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TestNewFlowBootComposition
{
    @Autowired
    private DataSource primaryDataSource;

    @Autowired
    @Qualifier("newFlowDataSource")
    private DataSource newFlowDataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private Flyway flyway;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TaskProvisioning taskProvisioning;

    @Test
    void oldPersistenceRemainsPrimaryAlongsideTheQualifiedNewFlowDatabase()
            throws Exception
    {
        assertThat(primaryDataSource).isNotSameAs(newFlowDataSource);
        try (Connection primary = primaryDataSource.getConnection();
                Connection newFlow = newFlowDataSource.getConnection()) {
            assertThat(primary.getMetaData().getURL())
                    .contains("bytequay-test-")
                    .doesNotContain("new-flow-test-");
            assertThat(newFlow.getMetaData().getURL())
                    .contains("bytequay-new-flow-test-");
        }
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(flyway.info().current()).isNotNull();
        assertThat(context.getBeansOfType(NewFlowDispatcher.Handler.class))
                .containsOnlyKeys("newFlowTaskProvisioning")
                .containsValue(taskProvisioning);
        assertThat(ReflectionTestUtils.getField(
                taskProvisioning, "runtime"))
                .isSameAs(context.getBean(FlowRuntime.class));
        assertThat(context.getBean(GitHubInitialPublishDispatcher.class))
                .isNotNull();
        assertThat(context.getBean(GitHubCiObservationDispatcher.class))
                .isNotNull();
        assertThat(context.getBean(GitHubCiUpdateDispatcher.class))
                .isNotNull();
        assertThat(context.getBean(CiAutofixDispatcher.class)).isNotNull();
    }
}
