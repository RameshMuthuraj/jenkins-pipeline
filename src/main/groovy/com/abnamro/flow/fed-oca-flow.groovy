package com.abnamro.flow

/**
 * This is an example flow for a npm/gulp application which has a static website as output.
 * These properties have to be here, they actually get saved after the first execection.
 * And they become normal Jenkins Job Parameters. Which is why we use "hidden", as we don't actually want to adjust them during a manual build.
 */

// Assert all required values for the flow
assert "$env.flowType" != null
assert "$env.branchType" != null
assert "$env.semVer" != null
assert "$env.componentScm" != null
assert "$env.componentBranch" != null
assert "$env.componentCredentialsId" != null
assert "$env.versionJob" != null
assert "$env.deployJob" != null
assert "$env.sonarJob" != null
assert "$env.curlNodeRestriction" != null
assert "$env.sonarNodeRestriction" != null
assert "$env.jenkinsCredentialsId" != null

def runFlow() {
    def steps
    def util
    node {
        pwd()
        unstash 'flowFiles'
        steps = load 'src/main/groovy/com/abnamro/flow/common/Steps.groovy'
        util = load 'src/main/groovy/com/abnamro/flow/common/Utilities.groovy'
        steps.initialize()
    }

    stage 'Execute Jenkins Pipeline'
    echo "BRANCH_NAME=$env.BRANCH_NAME"
    echo "push_commit=$push_commit"
    echo "flowType=$flowType"
    echo "sourceBranch=$sourceBranch"
    echo "sourceCommitHash=$sourceCommitHash"
    echo "targetBranch=$targetBranch"
    echo "curlNodeRestriction=$env.curlNodeRestriction"
    echo "sonarNodeRestriction=$env.sonarNodeRestriction"

    node ("$env.buildNodeRestriction") {
        env.workspace = steps.getWorkspace()
        ws(env.workspace) {
            // Execute the required steps
            echo "Working on Node $env.NODE_NAME @$env.workspace"
            steps.updateBuildDescription("$flowType", "$env.jenkinsCredentialsId")

            deleteDir()

            // create scm info object, and send use it to get do checkout
            env.branchName = env.BRANCH_NAME
            env.pushCommit = push_commit
            env.sourceBranch = sourceBranch
            env.sourceCommitHash = sourceCommitHash
            env.targetBranch = targetBranch
            // env.componentScm -> already in the env
            // env.componentCredentialsId -> already in the env
            steps.checkoutGitComponent()

            steps.npmBuild()

            if ( "$flowType" == "nightly" || "$flowType" == "release" || "$env.branchName" == "develop") {
                steps.sonarStage()
            }

            // Develop branch OR feature branch with flowType = release
            if ("$env.branchName" == "develop" || ("$env.branchName".contains('feature/') && "$flowType" == "release" ) ) {
                steps.npmApiDoc()
                def snapshot = "true"
                steps.npmPublish(snapshot)
                steps.publishOcaZipToNexus()
            }

            // for develop release
            if ( "$env.branchName" == "develop" && "$flowType" == "release") {
                def environmentName = '879'
                steps.udeployUTRedeploy(environmentName)
            }

            // For Relase branch and if and only IF flowType = release
            if ( "$flowType" == "release" && "$env.branchName".contains('release/') ) {
                steps.npmApiDoc()
                if (env.semVer == 'manual') {
                    // version is already manually updated
                    env.version = util.retrieveCurrentVersionFromPackageJson()
                } else {
                    steps.npmVersion()
                }
                steps.npmPublish()
                steps.publishOcaZipToNexus()
            }

            steps.stashSuccessNotification()
            if ( "$env.branchName" == "develop") {
                step([$class: 'Mailer', notifyEveryUnstableBuild: false, recipients: "$env.mailList", sendToIndividuals: false])
            }
        }
    }
}

// Has to exit with 'return this;' in order to be used as library
return this;
