# Technical Debt

The pipeline code often has to deal with situations that are not ideal.
Some problems are related to working on Windows instead of on Linux.
Not all plugins are compatible yet with the pipeline dsl, and sometimes we've not taken the time yet to refactor the code after a workaround is no longer needed.

# Windows vs. Linux problems

## Commandline command
The first obvious difference you run into, is that for windows you have to use **bat** instead of **sh** to execute commandline commands.
Most, if not all, examples are Linux based and will use the sh command.
The problem that comes with this, is that you will have to use the variables differently.

For Linux you can use the following:
```
node {
    def bar = 'bar'
    withEnv(["foo=$bar"]){
        sh 'echo foo${FOO}'
    }
}
```

For Windows, this will look like this (incl. convention).
```
node {
    def bar = 'bar'
    withEnv(["FOO=$bar"]){
        sh 'echo foo%FOO%'
    }
}
```


## Folders

### Delimeters
Folders work differently, first of all you have the different folder delimeter; **\** vs **/**.
So using commands via the **bat** dsl method, you will have to take this into account.

### Folder name truncate
Folder names in Windows have some restrictions and some interesting results.
Aside from being case insensitive, they often truncated as well.
Meaning; c:\someFolder\someTool\someFolder\whatever will become c:\someFolder\...ever\.

### Multi-Branch Pipeline
The multi-branch pipeline plugin will create jobs for you based on the branches.
Every job is created as a folder on the filesystem as well.

As with git, people will most likely use a form of the gitflow.
So they will have branches with slashes in their name, e.g. feature/jira-123-some-summary.
The default platform for Jenkins is Linux, where the filesystem delimter is **/**. 
So the branch name has to be normalized before it can be a folder name.
The solution, still at moment of writing [25-07-2016], is by url encode; i.e. feature%2Fjira-123-some-summary.

This works fine in Linux, but in Windows **%** is a keyword, used for variables.
Running a batch script (via **bat**) will hang indefinitely and cannot be killed via Jenkins GUI.


## Folder workaround
To resolve some of these problems, we will generate our own workspace folder name.
In the Steps.groovy file there's a utility method for doing this.
Then with the **ws(getWorkspace()) { } ** command we can use our own generated workspace name to create a work folder. 


# Missing plugin compatiblity
The Jenkins pipeline plugin introduced a new DSL and a new Job type.
Before a plugin can be used in this DSL or Job type, it needs to be altered.
As most plugins are community driven and some are reliant on the abstract job type lifecycle, the update process is slow.
So some plugins are not yet updated and thus not usable in the DSL, so we have to use an alternative of use the plugin in an alternative way.

## Current plugin workaround setup
* SonarQube
    * Initial workaround was a freestyle job that did the sonar analysis
    * As of 15-07-2016 we use the SonarQube Runner tool via the pipeline DSL 
* Git Publisher
    * The Jenkins Git Client plugin does support checkouts, but does not support the publish (git push) part
    * There are two workarounds possible: 1) use a freestyle job, 2) use the JGit tool to manually commit and push changes to origin 
* Stash Notifier
    * The current work around is to issue simple **curl** commands
* Http Request:
    * The http request plugin does not support the Credentials API, so we use curl commands with **withCredentials** DSL method
* UDeploy
    * The current work around is to issue simple **curl** commands
    * Another problem with this plugin is that it doesn't seem to support all the calls/properties we require
* HP Fortify
    * Use an external job with this configured
* HP ALM/UFT
    * Use an external job with this configured

For a list of plugins managed by cloudbees (in one way or another), see [https://github.com/jenkinsci/pipeline-plugin/blob/master/COMPATIBILITY.md](Jenkins Pipeline Compatibility List).


# Logic workarounds
Right now, several things are implemented in a naieve way.
Meaning, we progress through the pipeline sequentially and do not check for what work might have already been done.
e.g. it will always publish to Nexus, regardless of the exact version is already there - we then make the job unstable and go on instead failed.

## Sonar analysis
We do not check if the sonarqube analysis has already been done for this commit id.

## Nexus upload
We do not check if the current version is already in Nexus and simply follow the same old publish process.
This means we can spend about 5-10 minutes on this just to get a code 400 - Nexus telling us the artifact already exists.

## UDeploy re-deployment
Current the code for the UT Deploy stage always does the same routine:
* Always: Undeploy the current Snapshot from the VM
* Optionally: configure the component version on the snapshot (for details, see oca-pipeline-flowchart) 
* Always: Deploy the current Snapshot on the VM

It would be much more efficient to check up front if the deployed version is already the one we want.
If so, we don't have to do anything.

## Overall naieve implementation
We will go through the entire process, build, sonar, publish & deploy before checking anything.
Perhaps we only have to do a deploy and perhaps the exact version we want to deploy is already deployed.

From release branches we should deploy exact version (following true SemVer notation).
From other branches we deploy snapshots which have additional meta-data in their artifact name (i.e. short git commit hash, timestamp).
So we will always know up front if the desired version already exists or not and if it is deployed to the target environment already.
We should use this information to shorten the pipeline execution when one or more stages can be skipped as they are already in the desired state. 

# Linting
The Groovy linting via the Gradle build doesn´t work.
There should at least be some linting to aid developers working on the pipeline(s).