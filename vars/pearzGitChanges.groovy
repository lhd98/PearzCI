// Danh sách commit mới kể từ build thành công trước, dùng cho Telegram.

def collectGitChanges(int maximumChanges) {
    // Mốc là build THÀNH CÔNG gần nhất, không phải build gần nhất. Git
    // plugin ghi lại commit ngay ở bước checkout, nên một build bị huỷ
    // (bị push mới hơn thay thế) hoặc build hỏng vẫn kịp
    // đẩy GIT_PREVIOUS_COMMIT lên. Build chạy tới cùng sau đó sẽ tưởng
    // không có gì mới và báo "No new commits", dù chính nó tạo artifact.
    // GIT_PREVIOUS_SUCCESSFUL_COMMIT không phải lúc nào cũng được expose khi
    // dùng checkout(...) thủ công. Đọc thêm metadata của build thành công
    // trước đó để không rơi về git log -1 chỉ vì thiếu biến môi trường.
    def previousBuildCandidates = [
        [
            source: 'previous successful Jenkins build variables',
            commit: readPreviousSuccessfulBuildCommit()
        ],
        [
            source: 'GIT_PREVIOUS_SUCCESSFUL_COMMIT',
            commit: env.GIT_PREVIOUS_SUCCESSFUL_COMMIT?.trim()
        ],
        [
            source: 'GIT_PREVIOUS_COMMIT',
            commit: env.GIT_PREVIOUS_COMMIT?.trim()
        ]
    ].findAll { it.commit }
    def previousBuildCommit = null
    def previousBuildSource = 'none'

    for (def candidate : previousBuildCandidates) {
        if (isAncestorCommit(candidate.commit)) {
            previousBuildCommit = candidate.commit
            previousBuildSource = candidate.source
            break
        }
    }

    def hasValidPreviousCommit = previousBuildCommit != null
    def logOutput = ''

    echo(
        previousBuildCommit
            ? 'Telegram commit baseline: ' + previousBuildCommit +
                ' (source: ' + previousBuildSource + ').'
            : 'Telegram commit baseline unavailable; using HEAD only.'
    )
    def changes = collectJenkinsChangeSets()
    if (!changes) {
        withEnv([
        "PREVIOUS_BUILD_COMMIT=${previousBuildCommit ?: ''}",
        "HAS_VALID_PREVIOUS_COMMIT=${hasValidPreviousCommit}"
        ]) {
        logOutput = sh(
            script: '''
                if [ "$HAS_VALID_PREVIOUS_COMMIT" = "true" ]; then
                    git log --pretty=format:'%h%x09%an%x09%B%x1e' \
                        "$PREVIOUS_BUILD_COMMIT..HEAD"
                else
                    git log -1 --pretty=format:'%h%x09%an%x09%B%x1e'
                fi
            ''',
            returnStdout: true
        ).trim()
        }

    }

    if (!changes) {
        changes = logOutput
            .split('\u001e')
            .collect { record ->
                def fields = record.trim().split('\t', 3)
                if (fields.size() == 3) {
                    '- ' + fields[0] + ' - ' + fields[1] + ': ' +
                        formatCommitMessage(fields[2])
                } else {
                    ''
                }
            }
            .findAll { it }
    }

    if (changes) {
        previousBuildSource = previousBuildCommit
            ? previousBuildSource
            : 'Jenkins checkout changelog'
    }
    int totalChanges = changes.size()
    int hiddenCount = Math.max(0, totalChanges - maximumChanges)

    echo(
        'Telegram commit summary: source=' + previousBuildSource +
        ', total=' + totalChanges +
        ', shown=' + Math.min(totalChanges, maximumChanges) +
        ', hidden=' + hiddenCount + '.'
    )
    if (hasValidPreviousCommit && !changes) {
        return '- No new commits since the previous successful build.'
    }

    // Telegram giữ message ngắn; toàn bộ nội dung mỗi commit được gộp về
    // một dòng để markdown checklist trong body không làm vỡ bố cục.

    if (changes.size() > maximumChanges) {
        def limitedChanges = []

        limitedChanges.addAll(changes[0..(maximumChanges - 1)])
        limitedChanges << "- ... and ${hiddenCount} more commit(s)."
        changes = limitedChanges
    }

    return changes.join('\n')
}

def collectJenkinsChangeSets() {
    def changes = []

    try {
        currentBuild.changeSets?.each { changeSet ->
            changeSet.items?.each { entry ->
                def commit = entry.commitId?.toString()?.trim()
                def author = entry.author?.fullName?.toString()?.trim()
                def message = formatCommitMessage(entry.msg?.toString())

                if (commit && message) {
                    changes << '- ' + commit.take(7) + ' - ' +
                        (author ?: 'unknown') + ': ' + message
                }
            }
        }
    } catch (Exception exception) {
        echo(
            'Could not read the Jenkins checkout changelog: ' +
            exception.message
        )
    }

    return changes
}

def formatCommitMessage(String message) {
    if (!message?.trim()) {
        return ''
    }

    return message
        .readLines()
        .collect { it.trim().replaceFirst(/^[-*•]\\s+/, '') }
        .findAll { it }
        .join(' • ')
}

def readPreviousSuccessfulBuildCommit() {
    try {
        def previousSuccessfulBuild = currentBuild.previousSuccessfulBuild
        def buildVariables = previousSuccessfulBuild?.getBuildVariables()
        def commit = buildVariables?.get('PEARZCI_GIT_COMMIT')?.trim()

        if (!commit) {
            // Compatibility with successful builds created before v0.5.4.
            commit = buildVariables?.get('GIT_COMMIT')?.trim()
        }

        if (commit) {
            return commit
        }
    } catch (Exception exception) {
        echo(
            'Could not read the previous successful build variables: ' +
            exception.message
        )
    }

    return ''
}

def isAncestorCommit(String commit) {
    if (!commit?.trim()) {
        return false
    }

    withEnv(["PREVIOUS_BUILD_COMMIT=" + commit.trim()]) {
        return sh(
            script: '''
                git rev-parse --verify \
                    "$PREVIOUS_BUILD_COMMIT^{commit}" >/dev/null 2>&1 &&
                git merge-base --is-ancestor \
                    "$PREVIOUS_BUILD_COMMIT" HEAD
            ''',
            returnStatus: true
        ) == 0
    }
}
