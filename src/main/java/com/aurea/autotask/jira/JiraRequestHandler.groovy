package com.aurea.autotask.jira

import com.atlassian.jira.rest.client.api.JiraRestClient
import com.atlassian.jira.rest.client.api.NamedEntity
import com.atlassian.jira.rest.client.api.domain.Field
import com.atlassian.jira.rest.client.api.domain.Issue
import com.atlassian.jira.rest.client.api.domain.IssueType
import com.atlassian.jira.rest.client.api.domain.Transition
import com.atlassian.jira.rest.client.api.domain.input.ComplexIssueInputFieldValue
import com.atlassian.jira.rest.client.api.domain.input.FieldInput
import com.atlassian.jira.rest.client.api.domain.input.IssueInput
import com.atlassian.jira.rest.client.api.domain.input.IssueInputBuilder
import com.atlassian.jira.rest.client.api.domain.input.TransitionInput
import com.atlassian.jira.rest.client.api.domain.input.WorklogInput
import com.atlassian.jira.rest.client.api.domain.input.WorklogInputBuilder
import com.atlassian.util.concurrent.Promise
import groovy.util.logging.Log4j2
import one.util.streamex.StreamEx
import org.joda.time.DateTime

import static org.apache.commons.lang.StringUtils.substringAfter
import static org.apache.commons.lang.StringUtils.substringBefore

@Log4j2
class JiraRequestHandler {

    private JiraCache jiraCache
    private JiraRestClient client

    JiraRequestHandler(JiraCache jiraCache, JiraRestClient client) {
        this.jiraCache = jiraCache
        this.client = client
    }

    void transition(Map<String, String> transitionPath) {
        String currentStatus
        while ((currentStatus = issue.status.name.toUpperCase()) != jiraCache.desiredStatus) {
            log.info "Current status: '$currentStatus', next transition: '${transitionPath[currentStatus]}'"

            /* ToDo: Fix this dirty hack */
            transition(getByNameOrFail(transitions, transitionPath[currentStatus]))
        }
    }

    void addWorklog() {
        WorklogInput worklogInput = new WorklogInputBuilder(issue.getSelf()).setStartDate(new DateTime())
                .setComment("updated")
                .setMinutesSpent(jiraCache.timeSpent)
                .build()
        Promise<Void> result = client.issueClient.addWorklog(issue.getWorklogUri(), worklogInput)
        result.claim()
    }

    void transition(Transition transition) {
        log.info "Transitioning to ${transition}..."

        client.issueClient.transition(issue, new TransitionInput(transition.id)).claim()
        refreshIssue()
    }

    Iterable<Transition> getTransitions() {
        log.info 'Getting transitions'

        client.issueClient.getTransitions(issue).claim()
    }

    private refreshIssue() {
        assert jiraCache.issue

        log.info 'Refreshing issue'

        jiraCache.issue = client.issueClient.getIssue(issue.key).claim()
    }

    Issue getIssue() {
        if (jiraCache.issue) {
            return jiraCache.issue
        }

        log.info 'Getting issue'

        jiraCache.issue = client.issueClient.getIssue(issueKey).claim()
    }

    Issue getParentIssue() {
        if (jiraCache.parentIssue) {
            return jiraCache.parentIssue
        }

        log.info 'Getting parent issue'

        jiraCache.parentIssue = client.issueClient.getIssue(jiraCache.parentIssueKey).claim()
    }

    String getIssueKey() {
        if (jiraCache.issueKey) {
            return jiraCache.issueKey
        }

        def issueType = jiraCache.parentIssueKey ? jiraCache.issueSubTaskType : jiraCache.issueType
        def issueTypeId = getByNameOrFail(issueTypes, issueType).id

        def summary = jiraCache.summary
        def summaryPrefix = jiraCache.parentIssueKey ? parentIssue.summary + ' - ' : ''

        def builder = new IssueInputBuilder(jiraCache.projectKey, issueTypeId, summaryPrefix + createTitle(summary))

        if (jiraCache.reviewer) {
            log.info "Setting reviewer to '$jiraCache.reviewer'"
            def reviewerField = new FieldInput(reviewerField.id, ComplexIssueInputFieldValue.with('name', jiraCache.reviewer))
            builder.setFieldInput(reviewerField)
        }

        if (jiraCache.parentIssueKey) {
            builder.setFieldValue('parent', ComplexIssueInputFieldValue.with('key', jiraCache.parentIssueKey))
        }

        log.info 'Creating issue input'

        IssueInput issueInput = builder
                .setAssigneeName(jiraCache.assignee)
                .setDescription(createDescription(summary))
                .build()

        log.info 'Creating issue'

        def basicIssue = client.issueClient.createIssue(issueInput).claim()

        log.info "Issue created: $basicIssue.key"

        jiraCache.issueKey = basicIssue.key
    }

    Field getReviewerField() {
        if (jiraCache.reviewerField) {
            return jiraCache.reviewerField
        }

        log.info 'Getting reviewer field'

        jiraCache.reviewerField = getByNameOrFail(fields, 'Reviewer')
    }

    Iterable<Field> getFields() {
        if (jiraCache.fields) {
            return jiraCache.fields
        }

        log.info 'Requesting fields meta information ...'

        jiraCache.fields = client.metadataClient.fields.claim()
    }

    Iterable<IssueType> getIssueTypes() {
        if (jiraCache.issueTypes) {
            return jiraCache.issueTypes
        }

        log.info 'Requesting issue types ...'
        jiraCache.issueTypes = client.metadataClient.issueTypes.claim()
    }

    static <T extends NamedEntity> T getByNameOrFail(Iterable<T> entities, String name) {
        StreamEx.of(entities.iterator()).findFirst { it.name == name }.orElseThrow {
            String names = StreamEx.of(entities.iterator()).map { it.getName() }.joining(', ')
            new IllegalStateException("Failed to find named entity $name in [$names]")
        }
    }

    static createTitle(String summary) {
        substringBefore(summary, ':')
    }

    static createDescription(String summary) {
        def linePrefix = '* '

        linePrefix + substringAfter(summary, ':').split(';')
                *.trim()
                *.capitalize()
                .join(System.lineSeparator() + linePrefix)
    }
}
