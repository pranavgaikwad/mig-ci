# How to decrypt Mig CI debug log?

The `mig-ci` build proceeds in different steps. You can try to find details of the step that failed. If it isn't any useful, `mig-ci` also displays debug information as a separate step in the build process. This document helps users comprehend that cryptic debug log.

## Navigating to the debug log 

`mig-ci-robot` posts a comment on each PR after the build is finished. Navigate to the link posted in the comment. The link takes you to the full build log.

You will be redirected to a dashboard that looks like this : 

![mig-ci-dashboard](https://user-images.githubusercontent.com/9839757/80106700-344fb580-8548-11ea-87e6-9f7648185031.png)

From the different steps above, select _Gather debug info_ step. 

You will see debug information gathered from both Source and Destination clusters during the build :

![mig-ci-debug-log](https://user-images.githubusercontent.com/9839757/80112871-9829ac80-854f-11ea-9670-ea92f24e1a8d.gif)

## Understanding debug info

Click on any of the Debug Source or Debug Destination section to collapse the full text. Following instructions work for both Source and Destination debug information.

### Finding logs from pods

The debug output contains logs from various relevant pods. You can search for text `Process LOGS` to find the section where logs are printed in the output.

More specifically, you can search for following terms :

* Mig controller logs
* Velero logs
* Operator logs

### Finding images used for build

The debug output also prints images used for the build. Search for `Image` or `Dump all images` in the log output to find the details of images used during the build.

### Snapshot of openshift-migration namespace 

The debug script takes a snapshot of resources running during the build. You can search for text `Print all resources` to find all the resources that were running during the build in `openshift-migration` namespace.

### Finding info about Migration CRs

You can find (-o yaml) output for different migration resources by searching for following terms in the output : 
* migmigration 
* migplan
* migstorage
* migcluster