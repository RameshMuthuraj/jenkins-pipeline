package com.abnamro.flow.common
import groovy.json.JsonSlurper

// MAIN SCRIPT VARIABLES
def util() {
  unstash 'flowFiles'
  def util = load 'src/main/groovy/com/abnamro/flow/common/Utilities.groovy'
  return util
}

def udeploy() {
  unstash 'flowFiles'
  def udeploy = load 'src/main/groovy/com/abnamro/flow/common/UDeploy.groovy'
  return udeploy
}

// STAGES
// The stages do not acquire Nodes, you have to do so before using these methods.

/**
  * Does a GIT check out.
  * Will determine what kind of checkout to perform based upon the required parameters in the env.
  * The various variables of the SCMInfo object are expected to be filled.
  * If a value is not present, you will have to fill it with the value 'empty'.
  *
  * merge checkout if sourceBranch is not 'empty'
  *  this also assumes 'targetBranch' and 'sourceCommitHash' are filled
  *  these values can be derived from the StashPullRequestBuilder
  *  will checkout sourceCommitHash and merge with targetBranch
  *
  * commitId checkout if sourceBranch is 'empty' and pushCommit is not 'empty'
  *  will checkout the specific commit
  *
  * else will checkout the branch by its name (branchName)
  * Required env parameters:
  *  String branchName
  *  String pushCommit
  *  String sourceBranch
  *  String sourceCommitHash
  *  String targetBranch
  *  String componentScm
  *  String componentCredentialsId
  *
  */
def checkoutGitComponent() {
  try {
    wrap([$class: 'TimestamperBuildWrapper']) {
      timeout(time: 60, unit: 'MINUTES') {
              if ('empty' == env.sourceBranch) {
                  if (env.pushCommit == 'empty') {
                      scmCheckoutBranch(env.componentCredentialsId, env.componentScm, env.branchName)
                  } else {
                      scmCheckoutCommit(env.componentCredentialsId, env.componentScm, env.pushCommit)
                  }

              } else {
                  scmCheckoutPullrequest(env.componentCredentialsId, env.componentScm, env.sourceCommitHash, env.targetBranch)
              }
      } // timeout end
    } // timestamper end
  } catch (err) {
      echo "Caught: ${err}"
      stashFaillureNotification()
      error 'failed to checkout'
  }
}

def npmApiDoc() {
  stage 'Generate API Documentation'
  try {
    wrap([$class: 'TimestamperBuildWrapper']) {
      timeout(time: 30, unit: 'MINUTES') {
        dir('docs/api-documentation') {
          bat 'npm install gulp --cache-min 999999'
          bat 'npm install --cache-min 999999'
          def nodeModulesPath = "${env.workspace}\\node_modules\\.bin"
          withEnv(["PATH+WHATEVER=$nodeModulesPath"]) {
            bat 'gulp ngdocs'
            step([$class: 'JavadocArchiver', javadocDir: 'build', keepAll: false])
          } // withEnv
        } // dir
      } // timeout
    } // timestamper
  } catch (err) {
      echo "Caught: ${err}"
      archive 'npm-debug.log'
      currentBuild.result = 'UNSTABLE'
      // stashFaillureNotification()
      // error 'api doc generation'

  } // catch
} // method npmApiDoc

def npmBuild() {
    stage 'Run Build & Unit Tests'
    try {
      wrap([$class: 'TimestamperBuildWrapper']) {
        timeout(time: 60, unit: 'MINUTES') {
          stash includes: '.npmrc', name: 'npmrc'

          // if phantomjsZip is set on the env, copy it to the temp dir
          if (env.phantomjsZip != 'empty') {
            withEnv(["PHANTOMJSZIP=$env.phantomjsZip"]) {
              // due to problematic windows filesystem handling, this is the simplest solution
              try {
                bat 'mkdir %TMP%\\phantomjs\\'
              } catch (err) {
                echo "Caught: ${err}"
              }
              bat 'copy %PHANTOMJSZIP% %TMP%\\phantomjs\\'
            }
          }

          env.version = util().retrieveCurrentVersionFromPackageJson()

          bat 'call npm set progress=false'
          bat 'call npm install --cache-min 999999'
        } // timeout end
      } // timestamp end
    } catch (err) {
        echo "Caught: ${err}"
        archive 'npm-debug.log'
        stashFaillureNotification()
        error 'build failed'
    }
}

// Executing protractor tests on our UT deployment with a Selenium Grid
def ocaSeleniumGrid(deploymentMachine) {
    stage 'Run Protractor UIT'
    try {
      def targetMachineUrl = getOcaSeleniumMachineUrl(deploymentMachine)
        withEnv(["URL=$targetMachineUrl"]) {

            bat 'call npm set progress=false'
            bat 'call npm install protractor@2.1.0-abn-amro --cache-min 999999'
            bat 'call npm install protractor-html-screenshot-reporter --cache-min 999999'
            bat 'call npm install del --cache-min 999999'
            bat 'call node_modules\\.bin\\protractor test\\integration\\protractor.integration.conf.js --baseUrl %URL%'
        }
    } catch (err) {
        echo "Caught: ${err}"
        archive 'npm-debug.log'
        stashFaillureNotification()
        error 'protractor tests failed'
    }
}

def normalizedBranchName(branchName) {
  def normalizedBranchName = branchName
  normalizedBranchName = normalizedBranchName.replace("feature/", "f_")
  normalizedBranchName = normalizedBranchName.replace("bugfix/", "b_")
  normalizedBranchName = normalizedBranchName.replace("hotfix/", "h_")
  normalizedBranchName = normalizedBranchName.replace("release/", "r_")
  return normalizedBranchName
}

/**
  * Our SonarQube configuration is managed by Jenkins.
  * This means we cannot rely on any build tool plugin to do the sonar analysis run for us.
  * But, the SonarQube plugin in Jenkins is not compatible yet with Jenkins Pipeline.
  * So we use an external job, see the Jenkins Job Builder configuration for how to configure this job to be flexible enough for all the branches.
  */
def sonarStage() {
    stage 'Run SonarQube Analysis'
    // Because SonarQube plugin is not yet supported: http://jira.sonarsource.com/browse/SONARJNKNS-213
    timeout(time: 30, unit: 'MINUTES') {
      try {
          env.sonar_node = env.NODE_NAME

          echo "starting sonarqube with the following parameters:"
          echo "workspace=$env.workspace"
          echo "node=$env.sonar_node"
          echo "branch=$env.branchName"
          echo "version=$env.version"

          // Because we need to supply sonar which branch is being analysed, we have to set sonar.branch property
          // Unfortunately, sonar does not allow '/' in branch names, so we have to normalize this by replace the '/' with '_'
          // to avoid to long names in sonar that are hard to distingquish, we will shorten branch types to letters -> feature/ = f_
          def normalizedBranchName = normalizedBranchName(env.branchName)
          echo "Normalized branch name for sonar property to $normalizedBranchName"

          bat 'copy sonar.properties sonar-project.properties'
          def sonarQubeRunner = tool name: "$env.sonarRunner", type: 'hudson.plugins.sonar.SonarRunnerInstallation'
          bat "$sonarQubeRunner\\bin\\sonar-runner.bat -e -Dsonar.branch=$normalizedBranchName -Dsonar.projectVersion=$env.version"

          // As there is no clear link anymore to sonarqube, add this in the build description
          // Example:  http://sonar-server:9000/dashboard/index?id=groupid:artifactid
          def sonarQubeDashboardUrl = "${env.sonarBaseUrl}${env.sonarArtifactName}:${normalizedBranchName}"
          def currentDescription = currentBuild.description
          currentBuild.description = "$currentDescription <a href=\"$sonarQubeDashboardUrl\">[Sonar]</a>"
      } catch (err) {
          echo "Caught: ${err}"
          stashFaillureNotification()
          // as we currently have to few build slots and the possiblity of a deadlock
          // we can expect some sonar failures due to waiting for a build slot on the same nodeEligibility
          // so for now (23-05-2016) we will set the build to unstable if sonar fails -> there's no build breaker anyway
          // error 'sonarqube analysis failed'
          currentBuild.result = 'UNSTABLE'
      }
    } // timeout end
} // method end

/**
 * Retrieves the commit metadata from BitBucket -> a Json message with a lot of info
 * For the JSON slurper, see:
 * - https://issues.jenkins-ci.org/browse/JENKINS-32508
 * - http://docs.groovy-lang.org/latest/html/gapi/groovy/json/JsonSlurper.html
 */
def retrieveCommitMetadataFromBitBucket(project, repository, commitId) {
  withCredentials([[$class          : 'UsernamePasswordMultiBinding', credentialsId: "$env.componentCredentialsId",
          usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
    bat "curl -X GET -u %USERNAME%:%PASSWORD% \"https://p-bitbucket.nl.eu.abnamro.com:7999/rest/api/1.0/projects/${project}/repos/${repository}/commits/${commitId}\" > out.json"
  }
  def metadata = readFile('out.json')
  def result = util().readJson(metadata)
  return result
}

def npmSnapshotVersion() {
  stage 'Update Package Version'
  bat '''call git rev-parse --verify --short HEAD > gitcommit.txt'''
  def gitCommitHash = readFile('gitcommit.txt').trim()
  // Have to remove all files, else we cannot call npm version
  bat 'del gitcommit.txt'

  def commitMetadata = retrieveCommitMetadataFromBitBucket(env.componentProjectName, env.componentRepositoryName, env.gitcommit)
  
  // Make it a Long
  def commitTimestamp = commitMetadata.authorTimestamp + 0L

  // In order to get a sortable list of latest packages we need a timestamp in there
  //  unfortunately, a : is not allowed in the version, so we just club HHmm together
  java.util.Date originalDate = new java.util.Date(commitTimestamp)
  String reformattedDate = originalDate.format('yyyyMMddHHmm')

  def formattedVersion = util().retrieveCurrentVersionFromPackageJson()

  if ("$env.branchName" != 'develop') {
    def normalizedBranchName = normalizedBranchName(env.branchName)
    // npm version doesn't allow _'s so we should replace this with -'s
    normalizedBranchName = normalizedBranchName.replace('_', '-')
    formattedVersion = "$formattedVersion-$normalizedBranchName"
  }
  def snapshotVersion = "$formattedVersion-$reformattedDate-$gitCommitHash"
  env.version = snapshotVersion

  try {
    withEnv(["VERSION=$env.version"]) {
        bat '''call npm version --force --no-git-tag-version %VERSION% '''
    }
  } catch (err) {
    archive 'npm-debug.log'
    echo "Caught: ${err}"
  }
}

/**
  * Versioning the NPM way.
  * Here we also use an external job, as with GIT we have to push the changes to the repository.
  * You can have this done by a commandline call instead.
  */
def npmVersion() {
    stage 'Update Package Version'
    timeout(time: 30, unit: 'MINUTES') {
        // Because GIT Publisher is not yet supported: https://issues.jenkins-ci.org/browse/JENKINS-28335
        try {
            build job: "$env.versionJob",
              parameters: [
                [$class: 'StringParameterValue', name: 'semVer', value: "$semVer"],
                [$class: 'StringParameterValue', name: 'branch', value: "$env.BRANCH_NAME"],
                [$class: 'StringParameterValue', name: 'name', value: "$env.buildUserName"],
                [$class: 'StringParameterValue', name: 'email', value: "$env.buildUserEmail"]
              ]

            step([$class: 'CopyArtifact', filter: 'version.txt', fingerprintArtifacts: true, projectName: "$env.versionJob", selector: [$class: 'LastCompletedBuildSelector']])
            def updatedVersion = read
            env.version = readFile('version.txt').trim()
        } catch (err) {
            echo "Caught: ${err}"
            stashFaillureNotification()
            error 'versioning failed'
        }
    }
}

/**
 * This again used NPM, but adding more alternative based upon a parameter for a maven or gradle release build is simple.
 */
def npmPublish(def snapshot='false') {
  wrap([$class: 'TimestamperBuildWrapper']) {
      wrap([$class: 'MaskPasswordsBuildWrapper']) {
        timeout(time: 60, unit: 'MINUTES') {
            try {

                if ("$snapshot" == "true") {
                  npmSnapshotVersion()
                } else {

                  checkout([$class:
                    'GitSCM',
                    branches: [[name:  "$env.BRANCH_NAME"]],
                    doGenerateSubmoduleConfigurations: false,
                    extensions: [],
                    gitTool: "$env.gitTool",
                    submoduleCfg: [],
                    userRemoteConfigs: [[
                      credentialsId: "$env.componentCredentialsId",
                      url:  "$env.componentScm"
                    ]]]
                  )
                }

                // can only set the stage here, in case the snapshot needs to be created
                // as we do not want to copy the workspace again and again, we will reuse it
                stage 'Publish Package to Nexus'
                // if phantomjsZip is set on the env, copy it to the temp dir
                if (env.phantomjsZip != 'empty') {
                  withEnv(["PHANTOMJSZIP=$env.phantomjsZip"]) {
                    // due to problematic windows filesystem handling, this is the simplest solution
                    try {
                      bat 'mkdir %TMP%\\phantomjs\\'
                    } catch (err) {
                      echo "Caught: ${err}"
                    }
                    bat 'copy %PHANTOMJSZIP% %TMP%\\phantomjs\\'
                  }
                }

                def email = "FED@nl.abnamro.com" // fake email, but doesn't matter
                withCredentials([[$class: 'UsernamePasswordBinding', credentialsId: "$env.publishCredentialsId", variable: 'credentialsInput']]) {
                  writeFile encoding: 'UTF-8', file: 'in.txt', text: env.credentialsInput
                }

                bat 'del .npmrc'
                bat 'del out.txt'
                bat 'certutil /encode in.txt out.txt'
                def encodedCredentials = readFile file: 'out.txt'

                def step1 = encodedCredentials.replace("-----BEGIN CERTIFICATE-----", "")
                def step2 = step1.replace("-----END CERTIFICATE-----", "")
                def credentials = step2.trim()

                writeFile encoding: 'UTF-8', file: '.npmrc', text:
                """registry = ${npmRegistryUrl}
                    loglevel = info
                    email = ${email}
                    _auth = ${credentials}
                """

                try {
                    bat 'call npm publish'
                } catch(err) {
                    echo "Caught: ${err}"
                    def npmDebugLog = readFile 'npm-debug.log'
                    archive 'npm-debug.log'
                    // error publish Failed PUT 400 means the version already existed
                    // this is oke, not good but doesn't warrant failing the job
                    if (npmDebugLog.contains('publish Failed PUT 400')) {
                        echo "Artifact already exists in Nexus"
                    } else if (npmDebugLog.contains('publish Failed PUT 403')) {
                        error 'Publishing to Nexus failed: Not Authorized'
                        stashFaillureNotification()
                    } else {
                        error 'Publishing to Nexus failed: Unknown'
                        stashFaillureNotification()
                    }
                }

            } catch (err) {
                echo "Caught: ${err}"
                archive 'npm-debug.log'
                stashFaillureNotification()
                error 'publish to nexus failed'
            }  finally {
                bat 'del .npmrc'
            }
        } // timeout end
      } // mask passwords end
  } // timestamper end
} // method end

def publishOcaZipToNexus() {
  def version = env.version

  withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "$env.nexusUploadCredentialsId", passwordVariable: 'pass', usernameVariable: 'user']]) {
    withEnv(["version=$version", "ocaNexusPublishLocation=$env.ocaNexusPublishLocation"]) {
      // Each bat gets his own failure processing
      // This also means, that if all commands in one bat fail except the last one, it is still considered a success
      // So each command gets its on bat
      bat 'curl -O %ocaNexusPublishLocation%omnichannel-application/-/omnichannel-application-%version%.tgz'
      bat 'tar -xzvf omnichannel-application-%version%.tgz'
      bat 'curl -v -F r=FED-releases -F hasPom=false -F e=zip -F g=com.abnamro.omnichannel-frontend.omnichannel-applications -F c=BA115_WidgetDeliveryContent -F a=oca-cpp -F v=%version% -F p=zip -F file=@package/dist/BA115_WidgetDeliveryContent.zip -u %user%:%pass% https://p-nexus.nl.eu.abnamro.com:8443/nexus/service/local/artifact/maven/content'
    }
  }
}

/**
  * Once in Nexus, we do not build the application anymore.
  * Any further step that requires the artifact, like deployments, should get it from Nexus.
  */
def retrieveArtifactFromNexusStage() {
    stage 'Retrieve Deploy Artifact'
    wrap([$class: 'TimestamperBuildWrapper']) {
        try {
            timeout(time: 25, unit: 'MINUTES') {
              def version = env.version
              def downloadFile = "${env.nexusRepositoryUrl}${env.nexusComponentId}/-/${env.nexusComponentId}-${version}.tgz"

              withEnv(["DOWNLOADFILE=$downloadFile"]) {
                if (isUnix()) {
                  sh '''wget %DOWNLOADFILE% -O temp.tgz'''
                } else {
                  bat '''powershell -Command (New-Object Net.WebClient).DownloadFile('%DOWNLOADFILE%', 'temp.tgz')'''
                }
              }
              stash includes: 'temp.tgz', name: 'deployArtifact'
            }
        } catch (err) {
            echo "Caught: ${err}"
            stashFaillureNotification()
            error 'retrieve deploy artifact failed'
        }
    }
}

/**
  * The standards and guidelines application is a static website.
  * In this step we upload it to a webhost.
  */
def publishDocumentationStage() {
    stage 'Publish Documentation'
    input 'Do you want to publish the documentation?'

    wrap([$class: 'TimestamperBuildWrapper']) {
        try {
            unstash 'deployArtifact'
            echo '======|> Unpacking Deploy Archive <|======'
            sh '''tar xzvf temp.tgz'''

            echo '======|> Uploading Documentation to the Hosting server <|======'
            version = env.version

            dir('package/dist/') {
                def latestUrl = "${env.sitePublishLocation}/${env.nexusComponentId}/latest/{}"
                def versionUrl = "${env.sitePublishLocation}/${env.nexusComponentId}/${version}/{}"
                withEnv(["latestUrl=$latestUrl", "versionUrl=$versionUrl"]) {
                    withCredentials([[$class          : 'UsernamePasswordMultiBinding', credentialsId: "$env.sitePublishCredentialsId",
                                      usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
                        if (isUnix()) {
                          sh '''find . -type f -exec curl -v --user $USERNAME:$PASSWORD --upload-file ./{} $latestUrl \\;'''
                          sh '''find . -type f -exec curl -v --user $USERNAME:$PASSWORD --upload-file ./{} $versionUrl \\;'''
                        } else {
                          echo 'Not yet implemented'
                        }
                    }
                }
            }
        } catch (err) {
            echo "Caught: ${err}"
            stashFaillureNotification()
            error 'publish documentation failed'
        }
    }
}

def publishStaticDocumentation(){
  stage 'Publish Documentation'
  def version= env.version
  build job: "$env.deployJob", parameters: [
      [$class: 'StringParameterValue', name: 'version', value: "$version"]
  ]
}

// UTIL Methods
// While Jenkins-30744 is not fixed, we have do this. As Windows does not support "%" in folder names.
// note: this is a Windows problem
def getWorkspace() {
    if ("$env.workspaceWorkAround" == "true") {
      def newWorkspaceFolder = "E:\\SLU_Workspace\\jenkins\\workspace\\$env.JOB_NAME\\$env.EXECUTOR_NUMBER"
      return newWorkspaceFolder.replace("%2F", "_")
    }
    return pwd().replace("%2F", "_")
}

def scmCheckoutBranch(credentialsId, scmUrl, branch) {
  echo "Checking out branch: credentialsId=$credentialsId, branch=$branch, scmUrl=$scmUrl"

  checkout([$class:
    'GitSCM',
    branches: [[name:  "$branch"]],
    doGenerateSubmoduleConfigurations: false,
    extensions: [[$class: 'PruneStaleBranch'], [$class: 'CloneOption', noTags: true, reference: '', shallow: true]],
    gitTool: "$env.gitTool",
    submoduleCfg: [],
    userRemoteConfigs: [[
      credentialsId: "$credentialsId",
      url:  "$scmUrl"
    ]]]
  )

  bat '''git rev-parse --verify HEAD > gitcommit.txt'''
  env.gitcommit = readFile('gitcommit.txt').trim()
}

def scmCheckoutCommit(credentialsId, scmUrl, commithash) {
  echo 'Checking out commit'
  checkout([$class: 'GitSCM', branches: [[name: "$commithash"]], doGenerateSubmoduleConfigurations: false, extensions: [], gitTool: "$env.gitTool",submoduleCfg: [], userRemoteConfigs: [[credentialsId: "$credentialsId", url: "$scmUrl"]]])
  env.gitcommit = "$commithash"
}

def scmCheckoutPullrequest(credentialsId, scmUrl, commitHash, targetBranch) {
  echo 'Checking source branch & target branch'
  checkout changelog: true, poll: false, scm: [$class: 'GitSCM', branches: [[name: commitHash]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PreBuildMerge', options: [fastForwardMode: 'FF', mergeRemote: 'origin', mergeTarget: targetBranch]]], gitTool: "$env.gitTool", submoduleCfg: [], userRemoteConfigs: [[credentialsId: credentialsId, url: scmUrl]]]
  env.gitcommit = commitHash
}

def stashSuccessNotification() {
  wrap([$class: 'TimestamperBuildWrapper']) {
    url = "${env.notifyScmUrl}/rest/build-status/1.0/commits/${env.gitcommit}"
    echo "url? = ${url}"
    withEnv(["URL=$url"]) {
      withCredentials([[$class          : 'UsernamePasswordMultiBinding', credentialsId: "$env.componentCredentialsId",
          usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
        writeFile file: 'build.json', text: "{\"state\": \"SUCCESSFUL\", \"key\": \"${env.JOB_NAME}\", \"name\": \"${env.BUILD_TAG}\", \"url\": \"${env.BUILD_URL}\"}"
        archive '*.json'
        if (isUnix()) {
          sh '''curl -u $USERNAME:$PASSWORD -H "Content-Type: application/json" -X POST $URL -d @build.json'''
        } else {
          bat '''curl -u %USERNAME%:%PASSWORD% -H "Content-Type: application/json" -X POST %URL% -d @build.json'''
        }
      }
    }
  }
}

def stashFaillureNotification() {
  wrap([$class: 'TimestamperBuildWrapper']) {
    url = "${env.notifyScmUrl}/rest/build-status/1.0/commits/${env.gitcommit}"
    echo "url? = ${url}"
    withEnv(["URL=$url"]) {
      withCredentials([[$class          : 'UsernamePasswordMultiBinding', credentialsId: "$env.componentCredentialsId",
        usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
        writeFile file: 'build.json', text: "{\"state\": \"FAILED\", \"key\": \"${env.JOB_NAME}\", \"name\": \"${env.BUILD_TAG}\", \"url\": \"${env.BUILD_URL}\"}"
        archive '*.json'
        if (isUnix()) {
          sh '''curl -u $USERNAME:$PASSWORD -H "Content-Type: application/json" -X POST $URL -d @build.json'''
        } else {
          bat '''curl -u %USERNAME%:%PASSWORD% -H "Content-Type: application/json" -X POST %URL% -d @build.json'''
        }
      }
    }
  }
}

def updateBuildDescription(String description, String credentialsId) {
  currentBuild.description = "$description - [@$env.NODE_NAME]"
}

def determineGitFlowBranchType(branch) {
    def branchType = 'bug'
    if (branch == "develop" || branch == "master" || branch.startsWith('release/')) {
        branchType = 'stable'
    } else if (branch.startsWith('feature/')) {
        branchType = 'feature'
    }
    return branchType
}

def udeployUTRedeploy(environmentName) {
    /**
    * For each step, log request in "deploy.log"
    * - Undeploy snapshot from environment
    * - Retrieve versions of snapshot
    * - IF current snapshot oca-cpp version != build oca-cpp version, delete component's version from Snapshot
    * - Get component versions that exist
    * - If build version does not exist (by name), execute "create.artifact.version" process
    * - Add version to snapshot (application, snapshot, component and version by name)
    * - Deploy snapshot to environment
    * - Archive "deploy.log"
    */

    def udeployLog = "Starting UDeploy redeployment\n"
    def snapshot = "UT-${environmentName }-HEAD"
    def environment = "${env.udeployEnvPrefix}${environmentName}"
    udeployLog += "application=$env.udeployApplication\nsnapshot=$snapshot\nenvironment=$environment\nartifact=$env.udeployArtifact\nversion=$env.version\ncomponent=$env.udeployComponent\n"

    try {
        // first undeploy
        stage 'Undeploy from UT'
        udeployLog += "Starting undeploy\n"
        def undeployMessageFileName = udeploy().writeUndeployProcessRequestMessage(env.udeployApplication, snapshot, environment)
        def undeployResult = udeploy().executeAndWaitForProcess(undeployMessageFileName)
        echo "undeployResult=$undeployResult"
        udeployLog += "undeployResult=$undeployResult\n"

        stage 'Update version in UT'
        // retrieve versions of snapshot, returns array of components
        udeployLog += "Starting retrieve versions of snapshot\n"
        def snapshotComponentVersion = udeploy().retrieveCurrentComponentVersionsOfSnapshot(env.udeployApplication, snapshot)
        def componentVersion = 'empty'
        for (component in snapshotComponentVersion) {
            if (component.name == "$env.udeployComponent"){
                componentDesiredVersion = component.desiredVersions[0]
                if (componentDesiredVersion != null) { 
                  componentVersion = componentDesiredVersion.name

                }
            }
        }

        // lets see what to do next based upon the component version attached to the snapshot
        if (componentVersion == "$env.version") {
            // if version of component on the snapshot is the one we want, do nothing; we're good
            udeployLog += "Found desired version of component in snapshot already\n"
        } else {
            // if the version on the snapshot is not the one we want we have to do some more work
            if (componentVersion != 'empty') {
                // there is a different version of the component attached to the snapshot, we have to remove it first
                udeployLog += "Found undesired version of component in snapshot, removing\n"
                udeploy().deleteComponentVersionFromSnapshot(env.udeployApplication, snapshot, env.udeployComponent, componentVersion)
            }

            // the snapshot is now clear of any version of the component
            // so now we check if the version of the component exists yet
            udeployLog += "Retrieving existing component versions\n"
            def componentVersionList = udeploy().retrieveComponentVersionList(env.udeployComponent)
            def currentVersionExists = false
            for (versionItem in componentVersionList) {
                if (versionItem.name == "$env.version") {
                    currentVersionExists = true
                }
            }

            // if build-version doesn't exit: make a new version
            if (currentVersionExists == false) {
                udeployLog += "Current version not found for component, adding new component version \n"
                def newVersionMessageFileName = udeploy().writeNewComponentVersionRequestMessage(env.udeployApplication, snapshot, environment, env.udeployArtifact, env.version, env.udeployComponent)
                def newVersionResult = udeploy().executeAndWaitForProcess(newVersionMessageFileName)
                udeployLog += "newVersionResult=$newVersionResult\n"
            } else {
                udeployLog += "Current version already exists for component\n"
            }

            // now we're sure that the version of the component exists, we can attach it to the snapshot
            udeployLog += "Adding desired component version to snapshot\n"
            udeploy().addComponentVersionToSnapshot(env.udeployApplication, snapshot, env.udeployComponent, env.version)
        } // we're now sure that the snapshot is prepared


        // deploy
        stage 'Deploy to UT'
        udeployLog += 'Starting deploy\n'
        def deployMessageFileName = udeploy().writeDeployRequestMessage(env.udeployApplication, snapshot, environment)
        def deployResult = udeploy().executeAndWaitForProcess(deployMessageFileName)
        udeployLog += "deployResult=$deployResult\n"
        if (deployResult == 'FAULTED') {
            currentBuild.result = 'UNSTABLE'
        } else {
            currentBuild.result = 'SUCCESS'
        }
    } catch (err) {
        echo "Caught: ${err}"
        currentBuild.result = 'UNSTABLE'
    } finally {
        // lastly: write and archive log
        writeFile encoding: 'UTF-8', file: 'udeploy.log', text: udeployLog
        archive 'udeploy.log'
        def emailBody = createUdeployEmailBody(snapshot, environment)
        def emailSubject = createUdeployEmailSubject(environment, env.udeployComponent)
        emailext attachmentsPattern: 'udeploy.log', body: emailBody, replyTo: 'no-reply@jenkins.nl.eu.abnamro.com', subject: emailSubject, to: "$env.deployEmailGroup"
    }
}
def createUdeployEmailSubject(environment, component){
    return "deployment on $environment of $component by Jenkins [$currentBuild.result]" 
}

def createUdeployEmailBody(snapshot, environment){
    def body = """
        Jenkins job $env.JOB_NAME (build: $env.BUILD_NUMBER) did a UDeploy deployment.
        application=$env.udeployApplication
        snapshot=$snapshot
        environment=$environment
        version=$env.version
        component=$env.udeployComponent

        Please confirm the deployment was successful.

        $env.BUILD_URL
    """
    return body
}

def initialize(){
    wrap([$class: 'BuildUser']) {
        env.buildUserName = "$env.BUILD_USER"
        env.buildUserId = "$env.BUILD_USER_ID"
        env.buildUserEmail = "$env.BUILD_USER_EMAIL"
    }
}


// Has to exist with 'return this;' in order to be used as library
return this;
