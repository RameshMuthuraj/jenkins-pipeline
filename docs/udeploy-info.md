# UDeploy - Redeployment flow


* For each step, log request in "deploy.log"
* Undeploy snapshot from environment
* Retrieve versions of snapshot
* IF current snapshot oca-cpp version != build oca-cpp version, delete component's version from Snapshot 
* Get component versions that exist
  * If build version does not exist (by name), execute "create.artifact.version" process
* Add version to snapshot (application, snapshot, component and version by name)
* Deploy snapshot to environment
* Archive "deploy.log"

# Command reference

### Retrieve snapshot id by snapshot name

```
GET https://ucd-pr.nl.eu.abnamro.com:8443/cli/snapshot/getSnapshot?application=WAS_HAY&snapshot=UT-879-HEAD
```

**Some important properties**

```
{
	"application": {...}-
	"id": "5f9922f6-357e-4821-a067-521d5ee330f2"
	"name": "UT-879-HEAD"
	"created": 1450699190380
	"active": true
	"versionsLocked": false
	"configLocked": false
	"applicationId": "28d55538-0a75-43ae-89d4-f8226c2d32c9"
	"user": "Frank Streur (st5253)"
}
```


### Retrieve component versions from Snapshot
```
GET https://ucd-pr.nl.eu.abnamro.com:8443/rest/deploy/snapshot/5f9922f6-357e-4821-a067-521d5ee330f2/versions?rowsPerPage=10&pageNumber=2&sortType=asc
GET https://ucd-pr.nl.eu.abnamro.com:8443/cli/snapshot/getSnapshotVersions?application=WAS_HAY&snapshot=UT-879-HEAD
```

**Array of components:**
[x] = oca-cpp

```
[ ...,
	{
		"name": "WAS_HAY.oca-cpp"
		"id": "c5ef4399-f0ca-4211-be4b-21243a803dc5"
		"desiredVersions": [ {
			"id": "1033a6dd-c307-4b8f-aabd-adc4dcff421a"
			"name": "1.21.0-2-201606271643-c2e2522"
		}]
	}
]
``` 

### Remove component version from Snapshot

```
DELETE https://ucd-pr.nl.eu.abnamro.com:8443/rest/deploy/snapshot/5f9922f6-357e-4821-a067-521d5ee330f2/versions/1033a6dd-c307-4b8f-aabd-adc4dcff421a
PUT https://ucd-pr.nl.eu.abnamro.com:8443/cli/snapshot/removeVersionFromSnapshot?application=WAS_HAY&component=WAS_HAY.oca-cpp&snapshot=UT-879-HEAD&version=1.21.0-1-201606270857-af34cd4
```


### Get component versions

```
GET https://ucd-pr.nl.eu.abnamro.com:8443/cli/component/versions?component=WAS_HAY.oca-cpp
```

**Usefull properties:**

```
{
	"id": "eb19c793-eaa3-4860-b358-dd64a93c90b1" 
	"name": "1.21.0-1-201606270857-af34cd4"
}
```

### Add component version to Snapshot

```
PUT https://ucd-pr.nl.eu.abnamro.com:8443//cli/snapshot/addVersionToSnapshot?application=WAS_HAY&component=WAS_HAY.oca-cpp&snapshot=UT-879-HEAD&version=1.21.0-1-201606270857-af34cd4
```

### Get latest version of component
```
GET https://ucd-pr.nl.eu.abnamro.com:8443/rest/deploy/component/c5ef4399-f0ca-4211-be4b-21243a803dc5/latestVersion
``` 

### Links on Component Version
You can set specific links on a component version.
I would recommend setting a links to Jenkins jobs that did something with this, and a link to the source commit.

#### Add Link on component version
```
PUT https://ucd-pr.nl.eu.abnamro.com:8443/cli/version/addLink?component=WAS_HAY.oca-cpp&version=1.21.0-1-201606270857-af34cd4&linkName=deployed-by-jenkins&link=https://p-jenkins.nl.eu.abnamro.com:9443/view/FED-Folders/job/fed/job/oca/job/fed-oca-pipeline/branch/develop/262/
```

#### Retrieve Links
```
GET https://ucd-pr.nl.eu.abnamro.com:8443/cli/version/getLinks?component=WAS_HAY.oca-cpp&version=1.21.0-1-201606270857-af34cd4
```

### Statusses on Snapshot
You can set statuses on Snapshots freely.
I guess you can use these as flags.

### Set status on Snapshot
```
PUT https://ucd-pr.nl.eu.abnamro.com:8443/cli/snapshot/addStatusToSnapshot?application=WAS_HAY.oca-cpp&snapshot=UT-879-HEAD&statusName=NewStatus
``` 

```
GET https://ucd-pr.nl.eu.abnamro.com:8443/cli/snapshot/getStatusList?application=WAS_HAY.oca-cpp&snapshot=UT-879-HEAD
```

# UDeploy CLI/REST resources
* http://www.ibm.com/support/knowledgecenter/en/SS4GSP_6.2.0/com.ibm.udeploy.reference.doc/topics/rest_api_ref_examples.html?view=embed
* http://www.ibm.com/support/knowledgecenter/en/SS4GSP_6.2.0/com.ibm.udeploy.reference.doc/topics/rest_api_ref_example.html?view=embed
* http://www.ibm.com/support/knowledgecenter/en/SS4GSP_6.2.0/com.ibm.udeploy.api.doc/topics/rest_cli_application.html?view=embed
* http://www.ibm.com/support/knowledgecenter/en/SS4GSP_6.2.0/com.ibm.udeploy.api.doc/topics/rest_cli_application_addcomponenttoapp_put.html
* http://www.ibm.com/support/knowledgecenter/en/SS4GSP_6.2.0/com.ibm.udeploy.api.doc/topics/rest_cli_application_snapshotsinapplication_get.html
* http://www.ibm.com/support/knowledgecenter/en/SS4GSP_6.2.0/com.ibm.udeploy.api.doc/topics/rest_cli_component.html
* http://www.ibm.com/support/knowledgecenter/en/SS4GSP_6.2.0/com.ibm.udeploy.api.doc/topics/rest_cli_component_versions_get.html
* http://www.ibm.com/support/knowledgecenter/en/SS4GSP_6.2.0/com.ibm.udeploy.api.doc/topics/rest_cli_version_addstatus_put.html
* http://www.ibm.com/support/knowledgecenter/SS4GSP_6.2.0/com.ibm.udeploy.api.doc/topics/rest_cli_snapshot_addversiontosnapshot_put.html