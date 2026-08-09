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
package com.bytequay.app.developmentflow;

import com.bytequay.app.developmentflow.stage.CancellationToCleanupHandoff;
import com.bytequay.app.developmentflow.stage.CleanupCompletionHandoff;
import com.bytequay.app.developmentflow.stage.CleanupQuiescenceHandoff;
import com.bytequay.app.developmentflow.stage.CleanupStageManager;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.LocalToRemoteHandoff;
import com.bytequay.app.developmentflow.stage.PlanStageManager;
import com.bytequay.app.developmentflow.stage.PlanToLocalHandoff;
import com.bytequay.app.developmentflow.stage.ProvisionToPlanHandoff;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.RemoteTerminalToCleanupHandoff;
import com.bytequay.app.developmentflow.stage.ReplanHandoff;
import com.bytequay.app.developmentflow.stage.StageManager;
import com.bytequay.app.developmentflow.task.BrainVerdictHandoff;
import com.bytequay.app.developmentflow.task.TaskControlHandoff;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.developmentflow.task.creation.TaskCreationHandoff;
import com.bytequay.app.developmentflow.trunk.TrunkManager;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class TestDevelopmentFlowWriterBoundary
{
    @Test
    void eachManagerDependsOnlyOnItsOwnWriterPort()
    {
        assertWriterBoundary(
                TrunkManager.class, TrunkManager.Store.class,
                TaskManager.Store.class, StageManager.Store.class);
        assertWriterBoundary(
                TaskManager.class, TaskManager.Store.class,
                TrunkManager.Store.class, StageManager.Store.class);
        assertWriterBoundary(
                StageManager.class, StageManager.Store.class,
                TrunkManager.Store.class, TaskManager.Store.class);

        for (Class<?> useCase : List.of(
                BrainVerdictHandoff.class,
                ProvisionToPlanHandoff.class,
                PlanToLocalHandoff.class,
                LocalToRemoteHandoff.class,
                ReplanHandoff.class,
                CancellationToCleanupHandoff.class,
                CleanupCompletionHandoff.class,
                CleanupQuiescenceHandoff.class,
                RemoteTerminalToCleanupHandoff.class,
                TaskControlHandoff.class,
                TaskCreationHandoff.class)) {
            assertThat(Arrays.stream(useCase.getDeclaredFields()).map(Field::getType))
                    .doesNotContain(
                            TrunkManager.Store.class,
                            TaskManager.Store.class,
                            StageManager.Store.class);
        }
    }

    @Test
    void publicManagersExposeNoGenericTransitionOrUpdateCommand()
    {
        Set<Class<?>> managers = ImmutableSet.of(
                TrunkManager.class,
                TaskManager.class,
                PlanStageManager.class,
                LocalDevelopmentStageManager.class,
                RemoteDevelopmentStageManager.class,
                CleanupStageManager.class);
        assertThat(managers.stream()
                .flatMap(type -> Arrays.stream(type.getMethods()))
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .filter(name -> name.equals("transition")
                        || name.equals("update")
                        || name.equals("setState")
                        || name.equals("acceptBrainVerdict")
                        || name.equals("beginCanceledCleanup")
                        || name.equals("beginCompletionCleanup")
                        || name.equals("beginRemoteClosedCleanup")
                        || name.equals("finishCleanup")
                        || name.equals("acceptObservedMerged")
                        || name.equals("acceptObservedClosed")
                        || name.equals("acceptProvisioned")
                        || name.equals("finishPause")
                        || name.equals("finishResume")
                        || name.equals("finishArchive")
                        || name.equals("sealForReplan")
                        || name.equals("sealForTaskCancellation")
                        || name.equals("acceptQuiescence")))
                .isEmpty();
    }

    @Test
    void acceptedCrossOwnerFactsCannotBeConstructedByCallers()
    {
        assertThat(Arrays.stream(TaskManager.AcceptedBrainVerdict.class
                        .getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(Arrays.stream(CleanupStageManager.AcceptedCompletion.class
                        .getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(Arrays.stream(PlanStageManager.AcceptedCompletion.class
                        .getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(Arrays.stream(PlanStageManager.AcceptedOpening.class
                        .getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(Arrays.stream(LocalDevelopmentStageManager.AcceptedCompletion.class
                        .getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(Arrays.stream(TaskManager.StageOpening.class.getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(Arrays.stream(TaskManager.AcceptedReplan.class.getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(Arrays.stream(
                        TaskManager.AcceptedCancellation.class.getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(Arrays.stream(StageManager.AcceptedSeal.class.getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(Arrays.stream(TaskManager.AcceptedPause.class.getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(Arrays.stream(TaskManager.AcceptedResume.class.getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(Arrays.stream(TaskManager.AcceptedArchive.class.getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(Arrays.stream(
                        TaskManager.AcceptedCleanupQuiescence.class.getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(Arrays.stream(
                        TrunkManager.AuthorizedTaskCreation.class.getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(Arrays.stream(
                        LocalDevelopmentStageManager.AcceptedPublishAuthorization.class
                                .getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(Arrays.stream(
                        RemoteDevelopmentStageManager.AcceptedMergeAuthorization.class
                                .getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(Arrays.stream(
                        RemoteDevelopmentStageManager.AcceptedTerminal.class
                                .getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(Arrays.stream(StageManager.class.getDeclaredClasses())
                .map(Class::getSimpleName))
                .doesNotContain("FactCommand");
    }

    @Test
    void writerPortsAreOwnedByTheirDomainPackages()
    {
        assertThat(TrunkManager.Store.class.getEnclosingClass()).isEqualTo(TrunkManager.class);
        assertThat(TaskManager.Store.class.getEnclosingClass()).isEqualTo(TaskManager.class);
        assertThat(StageManager.Store.class.getEnclosingClass()).isEqualTo(StageManager.class);
        assertThat(TrunkManager.class.getPackageName())
                .isEqualTo("com.bytequay.app.developmentflow.trunk");
        assertThat(TaskManager.class.getPackageName())
                .isEqualTo("com.bytequay.app.developmentflow.task");
        assertThat(StageManager.class.getPackageName())
                .isEqualTo("com.bytequay.app.developmentflow.stage");
    }

    @Test
    void deliveryAndPresentationRolesCannotImportWriterPorts()
            throws IOException
    {
        Path sources = Path.of("src/main/java");
        try (var files = Files.walk(sources)) {
            List<Path> prohibitedRoles = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(TestDevelopmentFlowWriterBoundary::isProhibitedRole)
                    .toList();
            for (Path source : prohibitedRoles) {
                String contents = Files.readString(source);
                assertThat(contents)
                        .as("writer-port import in %s", source)
                        .doesNotContain(
                                "TrunkManager.Store",
                                "TaskManager.Store",
                                "StageManager.Store",
                                ".developmentflow.trunk.persistence.",
                                ".developmentflow.task.persistence.",
                                ".developmentflow.stage.persistence.");
                if (isV2DevelopmentFlowSource(source, contents)) {
                    assertThat(contents)
                            .as("direct persistence bypass in %s", source)
                            .doesNotContain(
                                    "JdbcTemplate",
                                    "NamedParameterJdbcTemplate",
                                    "JdbcClient",
                                    "EntityManager",
                                    "createNativeQuery")
                            .doesNotMatch("(?is).*(insert\\s+into|update|delete\\s+from)\\s+"
                                    + "(tasks|stage|task_current_stage|trunk_transition|"
                                    + "task_transition|stage_transition)\\b.*");
                }
            }
        }
    }

    @Test
    void concreteAggregateWritersMustLiveInDomainOwnedPersistencePackages()
            throws IOException
    {
        Path sources = Path.of("src/main/java");
        try (var files = Files.walk(sources)) {
            for (Path source : files
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                String contents = Files.readString(source);
                if (implementsStore(contents, "TrunkManager")) {
                    assertThat(normalized(source))
                            .contains("/developmentflow/trunk/persistence/");
                    assertPackagePrivateStore(source, contents);
                }
                if (implementsStore(contents, "TaskManager")) {
                    assertThat(normalized(source))
                            .contains("/developmentflow/task/persistence/");
                    assertPackagePrivateStore(source, contents);
                }
                if (implementsStore(contents, "StageManager")) {
                    assertThat(normalized(source))
                            .contains("/developmentflow/stage/persistence/");
                    assertPackagePrivateStore(source, contents);
                }
            }
        }
    }

    @Test
    void concreteAggregateWritersJoinTheCommandTransaction()
            throws IOException
    {
        Path sources = Path.of("src/main/java/com/bytequay/app/developmentflow");
        try (var files = Files.walk(sources)) {
            for (Path source : files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> normalized(path).contains("/persistence/"))
                    .toList()) {
                String contents = Files.readString(source);
                if (!implementsStore(contents, "TrunkManager")
                        && !implementsStore(contents, "TaskManager")
                        && !implementsStore(contents, "StageManager")) {
                    continue;
                }
                assertThat(contents)
                        .as("aggregate Store must use the transaction-bound connection: %s", source)
                        .contains("JdbcTemplate")
                        .doesNotContain(
                                "DataSource",
                                "getConnection(",
                                "PlatformTransactionManager",
                                "TransactionTemplate",
                                "@Transactional",
                                "SqliteTransactions");
            }
        }
    }

    @Test
    void managersOwnNoAsynchronousWorker()
    {
        assertThat(ImmutableSet.of(
                        TrunkManager.class,
                        TaskManager.class,
                        StageManager.class,
                        BrainVerdictHandoff.class).stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                .map(Field::getType)
                .filter(Executor.class::isAssignableFrom))
                .isEmpty();
    }

    @Test
    void initialV2TaskCreationEntersThroughTaskManager()
    {
        assertThat(Arrays.stream(TaskManager.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName))
                .contains("createTaskInCommand")
                .doesNotContain("create", "createTask", "startTask");
    }

    private static void assertWriterBoundary(
            Class<?> manager, Class<?> ownStore, Class<?>... foreignStores)
    {
        Set<Class<?>> dependencies = Arrays.stream(manager.getDeclaredFields())
                .map(Field::getType)
                .collect(Collectors.toSet());
        assertThat(dependencies).contains(ownStore).doesNotContain(foreignStores);
    }

    private static boolean isProhibitedRole(Path source)
    {
        String path = source.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        String file = source.getFileName().toString().toLowerCase(Locale.ROOT);
        return path.contains("/controller/")
                || path.contains("/scheduler/")
                || path.contains("/dispatcher/")
                || path.contains("/observer/")
                || path.contains("/projector/")
                || path.contains("/scheduled/")
                || path.contains("/scheduling/")
                || file.contains("dispatcher")
                || file.contains("observer")
                || file.contains("projector")
                || file.contains("scheduler")
                || file.contains("scheduled")
                || file.contains("listener")
                || file.contains("provider")
                || file.contains("callback");
    }

    private static boolean isV2DevelopmentFlowSource(Path source, String contents)
    {
        String path = normalized(source);
        return path.contains("/developmentflow/")
                || contents.contains("TrunkManager")
                || contents.contains("TaskManager")
                || contents.contains("StageManager");
    }

    private static String normalized(Path source)
    {
        return source.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static boolean implementsStore(String contents, String manager)
    {
        return contents.contains("implements " + manager + ".Store")
                || (contents.contains("import com.bytequay.app.developmentflow.")
                && contents.contains(manager + ".Store;")
                && contents.matches("(?s).*\\bimplements\\b[^\\{]*\\bStore\\b.*"));
    }

    private static void assertPackagePrivateStore(Path source, String contents)
    {
        assertThat(contents)
                .as("aggregate Store adapter must be package-private: %s", source)
                .doesNotMatch("(?s).*\\bpublic\\s+(?:final\\s+)?class\\s+\\w+"
                        + "[^\\{]*\\bimplements\\b[^\\{]*(?:Manager\\.Store|\\bStore\\b).*");
    }
}
