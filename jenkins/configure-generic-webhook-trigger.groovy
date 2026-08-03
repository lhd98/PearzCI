/*
 * Bootstrap Generic Webhook Trigger for all matching Pipeline jobs.
 *
 * Run this from Manage Jenkins -> Script Console as an administrator.
 * Keep dryRun=true for the first run, review the output, then run again
 * with dryRun=false to persist the changes.
 *
 * Prerequisite: Generic Webhook Trigger and Pipeline plugins are installed.
 */

import hudson.model.ParametersDefinitionProperty
import hudson.triggers.Trigger
import jenkins.model.Jenkins
import org.jenkinsci.plugins.gwt.GenericTrigger
import org.jenkinsci.plugins.gwt.GenericVariable
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.job.properties.PipelineTriggersJobProperty

// Change only this value between the review and apply runs.
def dryRun = true

final String repositoryVariable = 'PEARZ_WEBHOOK_REPOSITORY'
final String refVariable = 'PEARZ_WEBHOOK_REF'
final String regexpFilterText = "\$${repositoryVariable} \$${refVariable}"

def getDefaultParameterValue(WorkflowJob job, String parameterName) {
    def parameters = job.getProperty(ParametersDefinitionProperty.class)
    def definition = parameters?.getParameterDefinition(parameterName)

    if (definition == null) {
        return null
    }

    def defaultParameterValue = definition.getDefaultParameterValue()
    return defaultParameterValue?.getValue()?.toString()?.trim()
}

String normalizeRepository(Object repositoryValue) {
    def repository = repositoryValue?.toString()?.trim()

    if (!repository) {
        return null
    }

    // Accept HTTPS/HTTP, ssh://, and scp-style Git URLs.
    repository = repository.replaceFirst(
        /^[A-Za-z][A-Za-z0-9+.-]*:\/\/[^\/]+\/?/,
        ''
    )
    repository = repository.replaceFirst(
        /^[^\/]+@[^:\/]+:/,
        ''
    )
    repository = repository.replaceFirst(/[?#].*$/, '')
    repository = repository.replaceFirst(/\.git\/?$/, '')
    repository = repository.replaceAll(/^\/+|\/+$/, '')

    def pathParts = repository.split('/')

    // Also accept a host without a scheme, such as github.com/owner/repo.
    if (
        pathParts.size() == 3 &&
        pathParts[0] ==~ /(?i)(github\.com|[^.\/]+\.[^.\/]+)/
    ) {
        pathParts = pathParts[1..2]
    }

    if (pathParts.size() != 2 || pathParts.any { !it?.trim() }) {
        return null
    }

    return pathParts.collect { it.trim() }.join('/')
}

String normalizeBranch(Object branchValue) {
    def branch = branchValue?.toString()?.trim()

    if (!branch) {
        return null
    }

    branch = branch.replaceFirst(/^refs\/heads\//, '')
    branch = branch.replaceFirst(/^refs\/remotes\/origin\//, '')
    branch = branch.replaceFirst(/^origin\//, '')
    branch = branch.replaceFirst(/^\*\//, '')

    return branch?.trim() ?: null
}

String regexEscape(String value) {
    def specialCharacters = '\\.^$|()[]{}*+?'

    return value.collect { character ->
        def characterText = character.toString()
        specialCharacters.indexOf(characterText) >= 0
            ? "\\${characterText}"
            : characterText
    }.join('')
}

String webhookFilterExpression(String repository, String branch) {
    return '^' + regexEscape(repository) +
        ' refs/heads/' + regexEscape(branch) + '$'
}

GenericTrigger createGenericTrigger(String repository, String branch) {
    def filterText = '$PEARZ_WEBHOOK_REPOSITORY $PEARZ_WEBHOOK_REF'
    def trigger = new GenericTrigger(
        [
            new GenericVariable(
                'PEARZ_WEBHOOK_REPOSITORY',
                '$.repository.full_name'
            ),
            new GenericVariable('PEARZ_WEBHOOK_REF', '$.ref')
        ],
        [], // No request parameters.
        []  // No headers.
    )

    trigger.setCauseString(
        'Triggered by GitHub push: ' +
            filterText
    )
    trigger.setPrintContributedVariables(false)
    trigger.setPrintPostContent(false)
    trigger.setToken('')
    trigger.setTokenCredentialId('')
    trigger.setRegexpFilterText(filterText)
    trigger.setRegexpFilterExpression(
        webhookFilterExpression(repository, branch)
    )

    return trigger
}

boolean isGitHubPushTrigger(Trigger trigger) {
    // Avoid a hard dependency on the GitHub plugin just to remove its old
    // trigger. This also handles the historical package name if encountered.
    return trigger?.class?.name in [
        'com.cloudbees.jenkins.GitHubPushTrigger',
        'hudson.plugins.git.GitHubPushTrigger'
    ]
}

def configuredJobs = []
def skippedJobs = []
def jenkins = Jenkins.get()

jenkins.getAllItems(WorkflowJob.class).each { WorkflowJob job ->
    def repositoryUrl = getDefaultParameterValue(
        job,
        'PROJECT_REPOSITORY_URL'
    )
    def branchValue = getDefaultParameterValue(job, 'GIT_BRANCH')
    def repository = normalizeRepository(repositoryUrl)
    def branch = normalizeBranch(branchValue)

    if (!repository || !branch) {
        def missing = []

        if (!repository) {
            missing << 'PROJECT_REPOSITORY_URL'
        }
        if (!branch) {
            missing << 'GIT_BRANCH'
        }

        skippedJobs << [
            name: job.fullName,
            reason: 'missing or invalid ' + missing.join(' and ')
        ]
        println "[SKIP] ${job.fullName}: ${skippedJobs[-1].reason}"
        return
    }

    def triggerProperty = job.getProperty(PipelineTriggersJobProperty.class)
    def existingTriggers = triggerProperty
        ? new ArrayList(triggerProperty.getTriggers())
        : []
    def removedGenericTriggers = existingTriggers.count {
        it instanceof GenericTrigger
    }
    def removedGitHubTriggers = existingTriggers.count {
        isGitHubPushTrigger(it)
    }
    def retainedTriggers = existingTriggers.findAll {
        !(it instanceof GenericTrigger) && !isGitHubPushTrigger(it)
    }
    def filterExpression = webhookFilterExpression(repository, branch)

    println(
        "[${dryRun ? 'DRY RUN' : 'APPLY'}] ${job.fullName} -> " +
            "${repository} / ${branch} / ${regexpFilterText} / " +
            filterExpression +
            " (remove GenericTrigger=${removedGenericTriggers}, " +
            "GitHubPushTrigger=${removedGitHubTriggers})"
    )

    if (dryRun) {
        configuredJobs << job.fullName
        return
    }

    try {
        retainedTriggers << createGenericTrigger(repository, branch)

        // WorkflowJob.setTriggers replaces the property, stops old triggers,
        // and starts the new set. Other trigger types remain untouched.
        job.setTriggers(retainedTriggers)
        job.save()
        configuredJobs << job.fullName
    } catch (Exception exception) {
        def reason = "could not save trigger: ${exception.class.simpleName}: " +
            exception.message
        skippedJobs << [name: job.fullName, reason: reason]
        println "[ERROR] ${job.fullName}: ${reason}"
    }
}

println ''
println "${dryRun ? 'Dry run' : 'Configuration'} complete. " +
    "${configuredJobs.size()} job(s) ${dryRun ? 'would be ' : ''}updated."

if (skippedJobs) {
    println 'Skipped jobs:'
    skippedJobs.each { entry ->
        println "- ${entry.name}: ${entry.reason}"
    }
}

return [
    dryRun: dryRun,
    updated: configuredJobs,
    skipped: skippedJobs
]
