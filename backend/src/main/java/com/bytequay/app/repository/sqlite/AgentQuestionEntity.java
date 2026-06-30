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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** JPA row for an {@code agent_question}. {@code options} is a JSON string
 *  the store (de)serialises — the option objects need richer shape than the
 *  {@link StringListConverter} handles. */
@Entity
@Table(name = "agent_question")
class AgentQuestionEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "thread_id", nullable = false)
    private String threadId;

    @Column(name = "task_id")
    private String taskId;

    @Column(name = "tool_call_id")
    private String toolCallId;

    @Column(name = "question", nullable = false)
    private String question;

    @Column(name = "context")
    private String context;

    @Column(name = "options_json", nullable = false)
    private String optionsJson;

    @Column(name = "allow_free_form", nullable = false)
    private boolean allowFreeForm;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "answer_option_id")
    private String answerOptionId;

    @Column(name = "answer_free_form")
    private String answerFreeForm;

    @Column(name = "created_at_ms", nullable = false)
    private long createdAtMs;

    @Column(name = "answered_at_ms")
    private Long answeredAtMs;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getThreadId() { return threadId; }
    void setThreadId(String threadId) { this.threadId = threadId; }

    String getTaskId() { return taskId; }
    void setTaskId(String taskId) { this.taskId = taskId; }

    String getToolCallId() { return toolCallId; }
    void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }

    String getQuestion() { return question; }
    void setQuestion(String question) { this.question = question; }

    String getContext() { return context; }
    void setContext(String context) { this.context = context; }

    String getOptionsJson() { return optionsJson; }
    void setOptionsJson(String optionsJson) { this.optionsJson = optionsJson; }

    boolean isAllowFreeForm() { return allowFreeForm; }
    void setAllowFreeForm(boolean allowFreeForm) { this.allowFreeForm = allowFreeForm; }

    String getStatus() { return status; }
    void setStatus(String status) { this.status = status; }

    String getAnswerOptionId() { return answerOptionId; }
    void setAnswerOptionId(String answerOptionId) { this.answerOptionId = answerOptionId; }

    String getAnswerFreeForm() { return answerFreeForm; }
    void setAnswerFreeForm(String answerFreeForm) { this.answerFreeForm = answerFreeForm; }

    long getCreatedAtMs() { return createdAtMs; }
    void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }

    Long getAnsweredAtMs() { return answeredAtMs; }
    void setAnsweredAtMs(Long answeredAtMs) { this.answeredAtMs = answeredAtMs; }
}
