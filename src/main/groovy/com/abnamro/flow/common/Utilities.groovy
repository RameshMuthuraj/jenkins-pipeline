package com.abnamro.flow.common

import groovy.json.JsonSlurper

def readJson(text) {
    def jsonSlurper = new JsonSlurper()
    def response = jsonSlurper.parseText(text)
    jsonSlurper = null
    echo "response:$response"
    return response
}

def retrieveCurrentVersionFromPackageJson() {
    def packageJsonText = readFile encoding: 'UTF-8', file: 'package.json'
    def packageJson = readJson(packageJsonText)
    def currentVersion = packageJson.version
    return currentVersion
}


// Has to exist with 'return this;' in order to be used as library
return this;
