package com.abnamro.flow.common

def util() {
    unstash 'flowFiles'
    def util = load 'src/main/groovy/com/abnamro/flow/common/Utilities.groovy'
    return util
}

def retrieveSnapshotData(application, snapshot){
    def snapshotDataUrl = env.udeployCLIUrl + "snapshot/getSnapshot?application=${application}&snapshot=${snapshot}"
    def responseFileName = "snapshot-data-${snapshot}.json"

    withEnv(["URL=$snapshotDataUrl"]) {
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "$env.udeployCredentialsId", passwordVariable: 'pss', usernameVariable: 'usr']]) {
            bat "curl -k -u %usr%:%pss% -H \"Content-Type: application/json\" \"%URL%\" > $responseFileName"
            def responseText = readFile encoding: 'UTF-8', file: "$responseFileName"
            bat "rm $responseFileName"
            def snapshotData = util().readJson(responseText)
            return snapshotData
        } // end withCredentials
    } // end withEnv
} // end method

def retrieveCurrentComponentVersionsOfSnapshot(application, snapshot){
    def snapshotVersionsUrl = env.udeployCLIUrl + "snapshot/getSnapshotVersions?application=${application}&snapshot=${snapshot}"
    def responseFileName = "snapshot-versions-${snapshot}.json"

    withEnv(["URL=$snapshotVersionsUrl"]) {
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "$env.udeployCredentialsId", passwordVariable: 'pss', usernameVariable: 'usr']]) {
            bat "curl -k -u %usr%:%pss% -H \"Content-Type: application/json\" \"%URL%\" > $responseFileName"
            def responseText = readFile encoding: 'UTF-8', file: "$responseFileName"
            bat "rm $responseFileName"
            def snapshotVersions = util().readJson(responseText)
            return snapshotVersions
        } // end withCredentials
    } // end withEnv
} // end method

def deleteComponentVersionFromSnapshot(application, snapshot, component, version){
    def snapshotRemoveVersionUrl = env.udeployCLIUrl + "snapshot/removeVersionFromSnapshot?application=${application}&snapshot=${snapshot}&component=${component}&version=${version}"
    def responseFileName = "snapshot-remove-version-${snapshot}.json"

    withEnv(["URL=$snapshotRemoveVersionUrl"]) {
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "$env.udeployCredentialsId", passwordVariable: 'pss', usernameVariable: 'usr']]) {
            bat "curl -k -u %usr%:%pss% -H \"Content-Type: application/json\" -X PUT \"%URL%\" > $responseFileName"
            def responseText = readFile encoding: 'UTF-8', file: "$responseFileName"
            bat "rm $responseFileName"
        } // end withCredentials
    } // end withEnv
} // end method

def addComponentVersionToSnapshot(application, snapshot, component, version){
    def snapshotAddVersionUrl = env.udeployCLIUrl + "snapshot/addVersionToSnapshot?application=${application}&snapshot=${snapshot}&component=${component}&version=${version}"
    def responseFileName = "snapshot-add-version-${snapshot}.json"

    withEnv(["URL=$snapshotAddVersionUrl"]) {
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "$env.udeployCredentialsId", passwordVariable: 'pss', usernameVariable: 'usr']]) {
            bat "curl -k -u %usr%:%pss% -H \"Content-Type: application/json\" -X PUT \"%URL%\" > $responseFileName"
            def responseText = readFile encoding: 'UTF-8', file: "$responseFileName"
            bat "rm $responseFileName"
        } // end withCredentials
    } // end withEnv
} // end method

def retrieveComponentVersionList(component) {
    def componentVersionListUrl = env.udeployCLIUrl + "component/versions?component=$component"
    def responseFileName = "component-versions-${component}.json"

    withEnv(["URL=$componentVersionListUrl"]) {
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "$env.udeployCredentialsId", passwordVariable: 'pss', usernameVariable: 'usr']]) {
            bat "curl -k -u %usr%:%pss% -H \"Content-Type: application/json\" \"%URL%\" > $responseFileName"
            def responseText = readFile encoding: 'UTF-8', file: "$responseFileName"
            bat "rm $responseFileName"
            def componentVersions = util().readJson(responseText)
            return componentVersions
        } // end withCredentials
    } // end withEnv
} // end method


def executeProcess(messageFileName){
    def responseFileName = "response-${messageFileName}"
    withEnv(["URL=$env.udeployRequestUrl"]) {
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "$env.udeployCredentialsId", passwordVariable: 'pss', usernameVariable: 'usr']]) {
            bat "curl -k -u %usr%:%pss% -H \"Content-Type: application/json\" -X PUT %URL% -d @$messageFileName > $responseFileName"
            def undeployProcessText = readFile encoding: 'UTF-8', file: "$responseFileName"
            bat "rm $responseFileName"
            def processResponse = util().readJson(undeployProcessText)
            return processResponse
        }
    }
}

def executeAndWaitForProcess(messageFileName){
    def result = ''
    timeout(15) {
        def reponseFileName = "request-status-$messageFileName"
        def processResponse = executeProcess(messageFileName)
        def processIsFinished = 0
        while (processIsFinished == 0) {
            withEnv(["URL=$env.udeployRequestStatusUrl"]) {
                withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "$env.udeployCredentialsId", passwordVariable: 'pss', usernameVariable: 'usr']]) {
                    bat "curl -k -u %usr%:%pss% %URL%$processResponse.requestId > $reponseFileName"
                    def requestStatusText = readFile encoding: 'UTF-8', file: reponseFileName
                    def requestStatus = util().readJson(requestStatusText)
                    echo "Process Request [status=$requestStatus.status, result=$requestStatus.result]"
                    if (requestStatus.status == 'CLOSED' || requestStatus.status == 'FAULTED') {
                        processIsFinished = 1
                        result = requestStatus.result
                    } else {
                        sleep 30
                    }
                }
            }
        }
    }
    return result
}

def writeSimpleProcessRequestMessage(process, application, snapshot, environment, messageFileName) {
    writeFile file: messageFileName, text: """
        {
            \"application\":\"$application\",
            \"applicationProcess\":\"$process\",
            \"snapshot\": \"$snapshot\",
            \"environment\":\"$environment\"
        }
    """
    return messageFileName
}

def writeUndeployProcessRequestMessage(application, snapshot, environment) {
    def messageFileName = 'undeployMessage.json'
    return  writeSimpleProcessRequestMessage('undeploy',application, snapshot, environment, messageFileName )
}

def writeDeployRequestMessage(application, snapshot, environment) {
    def messageFileName = 'deployMessage.json'
    return  writeSimpleProcessRequestMessage('deploy',application, snapshot, environment, messageFileName )
}

def writeNewComponentVersionRequestMessage(application, snapshot, environment, artifactId, artifactVersion, component) {
    def messageFileName = 'newVersionMessage.json'
    writeFile file: messageFileName, text: """
        {
            \"application\": \"$application\",
            \"applicationProcess\": \"create.artifact.version\",
            \"snapshot\": \"$snapshot\",
            \"environment\": \"$environment\",
            \"component\": \"$component\",
            \"properties\":
                {
                    \"artifact.id\": \"$artifactId\",
                    \"artifact.version\": \"$artifactVersion\"
                 },
            \"versions\": [
                {
                    \"version\": \"$artifactVersion\",
                    \"component\": \"$component\"
                 }
            ]
        }
    """
    return messageFileName
} // end method

// Has to exist with 'return this;' in order to be used as library
return this;
