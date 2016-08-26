import groovy.json.JsonSlurper

def readJson(text) {
    def jsonSlurper = new JsonSlurper()
    def response = jsonSlurper.parseText(text)
    jsonSlurper = null
    echo "response:$response"
    return response
}

node {

    writeFile file: 'undeployMessage.json', text: '{"application":"WAS_HAY","applicationProcess":"undeploy", "snapshot": "UT-879-HEAD","environment":"WAS_DEV_HAY_vm00000879"}'
    stage 'Prepare process calls'
    writeFile file: 'newVersionMessage.json', text: '{"application": "WAS_HAY","applicationProcess": "create.artifact.version","snapshot": "UT-879-HEAD","environment": "WAS_DEV_HAY_vm00000879","component": "WAS_HAY.oca-cpp","properties": {"artifact.id": "oca-cpp","artifact.version": "1.20.0-11-201606151736-3e1e253"},"versions": [{"version": "1.20.0-11-201606151736-3e1e253","component": "WAS_HAY.oca-cpp"}]}'
    writeFile file: 'deployMessage.json', text: '{"application":"WAS_HAY","applicationProcess":"deploy", "snapshot": "UT-879-HEAD","environment":"WAS_DEV_HAY_vm00000879"}'
    archive '*.json'

    url = "https://ucd-pr.nl.eu.abnamro.com:8443/cli/applicationProcessRequest/request"
    requestUrl = "https://ucd-pr.nl.eu.abnamro.com:8443/cli/applicationProcessRequest/requestStatus?request="
    echo "url? = ${url}"

    withEnv(["URL=$url", "R_URL=$requestUrl"]) {
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'joost-udeploy', passwordVariable: 'pss', usernameVariable: 'usr']]) {

            stage 'Undeploy'
            bat '''curl -k -u %usr%:%pss% -H "Content-Type: application/json" -X PUT %URL% -d @undeployMessage.json > undeployProcess.json'''
            def undeployProcessText = readFile encoding: 'UTF-8', file: 'undeployProcess.json'
            def undeployProcess = readJson(undeployProcessText)
            sleep 240
            bat "curl -k -u %usr%:%pss% %R_URL%$undeployProcess.requestId > undeployResponse.json"
            def undeployResponseText = readFile encoding: 'UTF-8', file: 'undeployResponse.json'
            def undeployResponse = readJson(undeployResponseText)
            echo "Undeploy process [status=$undeployResponse.status, result=$undeployResponse.result]"

            stage 'New Version'
            bat '''curl -k -u %usr%:%pss% -H "Content-Type: application/json" -X PUT %URL% -d @newVersionMessage.json > versionProcess.json'''
            def versionProcessText = readFile encoding: 'UTF-8', file: 'versionProcess.json'
            def versionProcess = readJson(versionProcessText)
            sleep 90
            bat "curl -k -u %usr%:%pss% %R_URL%$versionProcess.requestId > versionResponse.json"
            def versionResponseText = readFile encoding: 'UTF-8', file: 'versionResponse.json'
            def versionResponse = readJson(versionResponseText)
            echo "Version update process [status=$versionResponse.status, result=$versionResponse.result]"

            stage 'Deploy'
            bat '''curl -k -u %usr%:%pss% -H "Content-Type: application/json" -X PUT %URL% -d @deployMessage.json > deployProcess.json'''
            def deployProcessText = readFile encoding: 'UTF-8', file: 'deployProcess.json'
            def deployProcess = readJson(deployProcessText)
            sleep 380
            bat "curl -k -u %usr%:%pss% %R_URL%$deployProcess.requestId > deployResponse.json"
            def deployResponseText = readFile encoding: 'UTF-8', file: 'deployResponse.json'
            def deployResponse = readJson(deployResponseText)
            echo "Deploy process [status=$deployResponse.status, result=$deployResponse.result]"

            jsonSlurper = null

            if (undeployResponse.result == 'FAULTED' || versionResponse.result == 'FAULTED' || deployResponse.result == 'FAULTED') {
                currentBuild.result = 'UNSTABLE'
            }
        }
    }

}
