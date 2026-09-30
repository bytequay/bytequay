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
package com.bytequay.app.context;

import com.bytequay.app.web.GlobalExceptionHandler;
import com.bytequay.core.context.RegisteredContextSource;
import com.bytequay.core.context.RepositoryNotebook;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestRepositoryContextController
{
    private static final String WORKSPACE = "workspace";
    private static final String ROOT = "/api/workspaces/" + WORKSPACE + "/repository-context";
    private static final String ENTRY_ID = "knowledge:path:plugin/trino-iceberg/";
    private static final String ENTRY_FIELDS = """
            "id":"knowledge:path:plugin/trino-iceberg/","scopeId":"path:plugin/trino-iceberg/",
            "title":"Iceberg","summary":"Connector overview","body":"Read table metadata before planning.","claims":[],"sourceIds":[]
            """;
    private static final String LESSON_FIELDS = """
            "scopeId":"project","title":"Metadata checks","summary":"Check the snapshot","body":"Validate the current table snapshot.",
            "reviewId":"review","runId":"run","evidenceRefs":["E1"],"messageId":"message"
            """;

    private final RepositoryContextService service = mock(RepositoryContextService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final ContextGradeService grades = mock(ContextGradeService.class);
    private final ContextPageCaptures pages = mock(ContextPageCaptures.class);
    private final MockMvc mvc = standaloneSetup(new RepositoryContextController(service, grades, pages))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void listsTheSourcesWhosePageALaterCaptureFoundChanged()
            throws Exception
    {
        RepositoryNotebook notebook = RepositoryNotebook.empty();
        when(service.getNotebook("workspace")).thenReturn(notebook);
        when(pages.changed("workspace", notebook)).thenReturn(ImmutableMap.of("context-read:page", Instant.parse("2026-09-30T00:00:00Z")));

        mvc.perform(get("/api/workspaces/workspace/repository-context/pages/changed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed['context-read:page']").exists());
    }

    @Test
    void notebookResponseIncludesTheMatchDiscriminator()
            throws Exception
    {
        when(service.getNotebook(WORKSPACE)).thenReturn(RepositoryNotebook.empty());

        mvc.perform(get(ROOT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.useInReviews").value(true))
                .andExpect(jsonPath("$.useInDevelopment").value(true))
                .andExpect(jsonPath("$.scopes[0].match.kind").value("REPOSITORY"));
    }

    @Test
    void configurationKeepsSourceAuthorityInTheService()
            throws Exception
    {
        when(service.configure(eq(WORKSPACE), eq(0L), any())).thenReturn(RepositoryNotebook.empty());

        mvc.perform(put(ROOT + "/configuration").contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "expectedRevision":0,"useInReviews":false,"useInDevelopment":false,
                          "sources":[{"id":"iceberg","kind":"DOCUMENTATION_ROOT","url":"https://iceberg.apache.org/docs/latest/","origin":"GENERATED","discoveryEvidenceRef":"fake"}],
                          "sourceEvidence":[{"id":"fake","excerpt":"Unverified supplied evidence"}],"revision":99
                        }
                        """))
                .andExpect(status().isOk());

        ArgumentCaptor<EditContextConfiguration> edit = ArgumentCaptor.forClass(EditContextConfiguration.class);
        verify(service).configure(eq(WORKSPACE), eq(0L), edit.capture());
        assertThat(edit.getValue().useInReviews()).contains(false);
        assertThat(edit.getValue().useInDevelopment()).contains(false);
        assertThat(edit.getValue().sources()).contains(ImmutableList.of(new EditContextConfiguration.Source(
                "iceberg", RegisteredContextSource.Kind.DOCUMENTATION_ROOT, "https://iceberg.apache.org/docs/latest/")));
    }

    @Test
    void configurationOmittedFieldsRetainTheirAbsence()
            throws Exception
    {
        when(service.configure(eq(WORKSPACE), eq(3L), any())).thenReturn(RepositoryNotebook.empty());

        mvc.perform(put(ROOT + "/configuration").contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":3,\"useInReviews\":true}"))
                .andExpect(status().isOk());

        verify(service).configure(WORKSPACE, 3, new EditContextConfiguration(Optional.of(true), Optional.empty(), Optional.empty()));
    }

    @Test
    void entryIdsWithSlashesRemainInRequestBodies()
            throws Exception
    {
        when(service.editEntry(eq(WORKSPACE), eq(4L), any())).thenReturn(RepositoryNotebook.empty());
        when(service.setEntryEnabled(WORKSPACE, 5, ENTRY_ID, false)).thenReturn(RepositoryNotebook.empty());

        mvc.perform(put(ROOT + "/entries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":4," + ENTRY_FIELDS + ",\"origin\":\"GENERATED\",\"version\":999,\"enabled\":false}"))
                .andExpect(status().isOk());
        mvc.perform(put(ROOT + "/entries/enabled").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":5,\"entryId\":\"" + ENTRY_ID + "\",\"enabled\":false}"))
                .andExpect(status().isOk());

        verify(service).editEntry(WORKSPACE, 4, new EditContextEntry(ENTRY_ID, "path:plugin/trino-iceberg/", "Iceberg", "Connector overview",
                "Read table metadata before planning.", ImmutableList.of(), ImmutableList.of()));
        verify(service).setEntryEnabled(WORKSPACE, 5, ENTRY_ID, false);
    }

    @Test
    void restoreAndRunNoteDisablingPassOnlyTheirExplicitFields()
            throws Exception
    {
        when(service.restoreEntry(WORKSPACE, 4, ENTRY_ID)).thenReturn(RepositoryNotebook.empty());
        when(service.disableRunNotes(WORKSPACE, 5, "task-1", ImmutableList.of(ENTRY_ID))).thenReturn(RepositoryNotebook.empty());

        mvc.perform(post(ROOT + "/entries/restore").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":4,\"entryId\":\"" + ENTRY_ID + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post(ROOT + "/runs/disable-notes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":5,\"taskId\":\"task-1\",\"noteIds\":[\"" + ENTRY_ID + "\"]}"))
                .andExpect(status().isOk());

        verify(service).restoreEntry(WORKSPACE, 4, ENTRY_ID);
        verify(service).disableRunNotes(WORKSPACE, 5, "task-1", ImmutableList.of(ENTRY_ID));
    }

    @Test
    void reportsTakeOnlyTheUsersReasonAndKeepNeedsTheShownRevision()
            throws Exception
    {
        when(grades.reportedNotes(WORKSPACE)).thenReturn(ImmutableList.of());
        when(service.keepEntry(WORKSPACE, 4, ENTRY_ID)).thenReturn(RepositoryNotebook.empty());
        when(service.getDisputes(WORKSPACE)).thenReturn(new ContextDisputes(ImmutableMap.of(ENTRY_ID, new ContextDisputes.Contest("A statement", 3)), ImmutableMap.of()));

        mvc.perform(get(ROOT + "/reports")).andExpect(status().isOk());
        mvc.perform(get(ROOT + "/disputes")).andExpect(status().isOk())
                .andExpect(jsonPath("$.contested['" + ENTRY_ID + "'].statement").value("A statement"));
        mvc.perform(post(ROOT + "/reports").contentType(MediaType.APPLICATION_JSON).content("{\"taskId\":\"task-1\",\"entryId\":\"" + ENTRY_ID
                        + "\",\"entryVersion\":3,\"reason\":\"Wrong path\",\"grade\":\"HELPED\",\"grader\":\"AGENT\"}"))
                .andExpect(status().isOk());
        mvc.perform(post(ROOT + "/reports").contentType(MediaType.APPLICATION_JSON).content("{\"taskId\":\"task-1\",\"entryId\":\"" + ENTRY_ID
                        + "\",\"reason\":\"No version\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(ROOT + "/entries/keep").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":4,\"entryId\":\"" + ENTRY_ID + "\"}"))
                .andExpect(status().isOk());

        verify(grades).report(WORKSPACE, "task-1", ENTRY_ID, 3, "Wrong path");
        verify(service).keepEntry(WORKSPACE, 4, ENTRY_ID);
    }

    @Test
    void lessonUsesOnlyExplicitUserFields()
            throws Exception
    {
        when(service.saveLesson(eq(WORKSPACE), eq(7L), any())).thenReturn(RepositoryNotebook.empty());

        mvc.perform(post(ROOT + "/lessons").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":7," + LESSON_FIELDS + ",\"excerpt\":\"Invented source text\",\"origin\":\"GENERATED\",\"id\":\"chosen-id\"}"))
                .andExpect(status().isOk());

        verify(service).saveLesson(WORKSPACE, 7, new SaveContextLesson("project", "Metadata checks", "Check the snapshot", "Validate the current table snapshot.",
                "review", "run", ImmutableList.of("E1"), Optional.of("message")));
    }

    @Test
    void everyMutationRequiresANonnegativeRevision()
            throws Exception
    {
        for (Mutation mutation : ImmutableList.of(
                new Mutation(HttpMethod.PUT, "/configuration", "\"useInReviews\":true"),
                new Mutation(HttpMethod.PUT, "/entries", ENTRY_FIELDS),
                new Mutation(HttpMethod.POST, "/entries/restore", "\"entryId\":\"knowledge:project\""),
                new Mutation(HttpMethod.POST, "/runs/disable-notes", "\"taskId\":\"task-1\",\"noteIds\":[]"),
                new Mutation(HttpMethod.POST, "/entries/keep", "\"entryId\":\"knowledge:project\""),
                new Mutation(HttpMethod.PUT, "/entries/enabled", "\"entryId\":\"knowledge:project\",\"enabled\":false"),
                new Mutation(HttpMethod.POST, "/lessons", LESSON_FIELDS))) {
            for (String revision : ImmutableList.of("", "\"expectedRevision\":null,", "\"expectedRevision\":-1,")) {
                mvc.perform(request(mutation.method(), ROOT + mutation.route()).contentType(MediaType.APPLICATION_JSON)
                                .content("{" + revision + mutation.fields() + "}"))
                        .andExpect(status().isBadRequest());
            }
        }
        verifyNoInteractions(service);
    }

    @Test
    void missingEnabledFlagIsRejected()
            throws Exception
    {
        mvc.perform(put(ROOT + "/entries/enabled").contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":0,\"entryId\":\"knowledge:project\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void missingSourceAndEntryFieldsAreBadRequests()
            throws Exception
    {
        mvc.perform(put(ROOT + "/configuration").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"sources\":[{\"id\":\"docs\",\"kind\":\"DOCUMENTATION_ROOT\"}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(ROOT + "/entries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"id\":\"knowledge:project\",\"claims\":[],\"sourceIds\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(ROOT + "/lessons").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"evidenceRefs\":[\"E1\"]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void staleRevisionAndMissingWorkspaceAreReportedWithoutRetrying()
            throws Exception
    {
        when(service.configure(eq(WORKSPACE), eq(3L), any())).thenThrow(new ContextConflictException(4));
        when(service.getNotebook(WORKSPACE)).thenThrow(new ContextNotFoundException("Workspace no longer exists"));

        mvc.perform(put(ROOT + "/configuration").contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":3,\"useInReviews\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentRevision").value(4));
        mvc.perform(get(ROOT)).andExpect(status().isNotFound());
        verify(service).configure(WORKSPACE, 3, new EditContextConfiguration(Optional.of(false), Optional.empty(), Optional.empty()));
        verify(service).getNotebook(WORKSPACE);
    }

    private record Mutation(HttpMethod method, String route, String fields) {}
}
