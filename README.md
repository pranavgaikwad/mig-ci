
# OCP MIG CI

## Purpose

This repo contains ansible and CI related assets used for the OCP 3 to 4 migration project, the primary use case for these tools is to integrate with Jenkins and allow the creation of all necessary CI workflows.

## Pipelines 

Jenkins _pipelines_ are used to provide the logic necessary to orchestrate the build and execution of CI workflows. Each pipeline job can be parameterized to customize the behavior for the intended workflow, they are also responsible of providing notifications for each build, below are the supplied  [mig CI pipelines](https://github.com/fusor/mig-ci/tree/master/pipeline) with a brief description:

| Pipeline | Purpose |
| --- | --- |
| `ocp3-agnosticd-base` | Deploys OCP3 using [agnosticd](https://github.com/fbladilo/testing#ocp3-agnosticd-multinode-in-aws), performs cluster sanity checks, multi-node cluster support |
| `ocp4-base` | Deploys OCP4 and performs cluster sanity checks |
| `parallel-base` | Deploys OCP3, OCP4 in parallel, installs cluster application migration tools and executes e2e migration tests|
| `cpma-e2e-base` | Works similar to `parallel-base`, deploys OCP3, OCP4, builds CPMA. After that extracts manifests from source cluster (OCP3) and applies them to the target cluster (OCP4). Arguments used in this pipeline are documented in https://github.com/fusor/cpma#e2e-tests |
| `cpma-base` | Same as above, but expects only preprovisioned stable cluster to compare generated reports. Does not check the manifests extraction. Documented in https://github.com/fusor/cpma#e2e-tests |

### CI job logic

The use of _**trigger jobs**_ which are parameterized is key to the structure of the CI workflows, trigger jobs can for instance watch repositories for activity and execute _**base pipelines**_ to run a CI workflow. A good example are trigger jobs watching the [mig-controller repo](https://github.com/fusor/mig-controller) for changes and executing the [parallel base pipeline](https://github.com/fusor/mig-ci/blob/master/pipeline/parallel-base.groovy) with [e2e tests](https://github.com/fusor/mig-e2e)

### CI job parameters

Below are some of the parameters allowing the customization of mig CI jobs :

| Parameter | Purpose | Notes |
| --- | --- | --- |
| `AWS_REGION` | AWS region for resources to deploy | Default varies based on pipeline |
| `OCP3_VERSION`| OCP3 version to deploy | Default is v3.11 |
| `OCP4_VERSION`| OCP4 version to deploy | Default is v4.1 |
| `NODE_COUNT` | Number of compute nodes to create | Same for source and target clusters, does not affect OA/origin3-dev clusters |
| `MASTER_COUNT` | Number of master nodes to create | Same for source and target clusters, does not affect OA/origin3-dev clusters |
| `CLUSTER_NAME` | Name of the cluster to deploy | The final deployment will use the following convention: `${CLUSTER_NAME}-v3-${BUILD_NUMBER}-${OCP3_VERSION}`. In AWS you can this value on instance tags GUID label|
| `EC2_KEY` | Name of SSH public and private key | Default is `ci`, outside CI `libra` is recommended. Will be used to allow SSH access to instances |
| `DEPLOYMENT_TYPE` | OCP3 deployment type | Could be `agnosticd`, `OA` or `cluster_up`|
| `MIG_CONTROLLER_REPO` | source repository for mig-controller to test | Default is fusor |
| `MIG_CONTROLLER_BRANCH` | source branch for mig-controller to test | Default is master |
| `SUB_USER` | RH subscription username | Only used in OA deployments to access OCP bits |
| `SUB_PASS` | RH subscription password | Only used in OA deployments to access OCP bits |
| `CLEAN_WORKSPACE` | Clean Jenkins workspace after build | Default is true |
| `EC2_TERMINATE_INSTANCES` | Terminate all instances on EC2 after build | Default is true |


#### CPMA CI job parameters

| Parameter | Purpose | Notes |
| --- | --- | --- |
| `EC2_PRIV_KEY`  | Private key for accessing instances, from Jenkins credentials store | Should be one of credentials in Jenkins |
| `EC2_KEY` | EC2 SSH key name for remote access | `ci` by default |
| `CPMA_BRANCH` | CPMA branch to checkout | `master` by default |
| `CPMA_REPO` | CPMA repo to clone | `https://github.com/fusor/cpma.git` points to upstream by default |
| `CPMA_HOSTNAME` | Hostname of the stable cluster for ssh access | Required to be specified |
| `CPMA_CLUSTERNAME`  | Cluster master name to generate report from. | Should be equal to `current-context`  |
| `CPMA_LOGIN`  | Login for the cluster | required |
| `CPMA_PASSWD` | Password for the cluster | required |
| `CPMA_SSHLOGIN` | SSH login for master node | `root` |
| `CPMA_SSHPORT`  | SSH port for master node | `22` |

_**Note:**_ **For a full list of all possible parameters please inspect each pipeline script**

### Migration controller e2e tests

The migration controller e2e tests are supplied in the [mig-e2e repo](https://github.com/fusor/mig-e2e), the tests are based on [mig controller sample scenarios](https://github.com/fusor/mig-controller/tree/master/docs/scenarios) and are executed during the last stage of CI jobs.

### Debugging CI jobs

The `EC2_TERMINATE_INSTANCES` and `CLEAN_WORKSPACE` boolean parameters can be used to **avoid** the termination of clusters and the cleanup of Jenkins workspace in case you want to debug a migration job after the run: 

1) SSH to jenkins host
2) Go to `/var/lib/jenkins/workspace`, and enter the `parallel-mig-ci-${BUILD_NUMBER}` dir. The `kubeconfigs` directory will contain `KUBECONFIG` files with active sessions for both source and target clusters. You can utilize them by `export KUBECONFIG=$(pwd)/kubeconfigs/ocp-v3.11-kubeconfig`.
3) Once done debugging, you can clean up resources manually by executing `./destroy_env.sh`.

## External NFS server setup on AWS

In order to demonstrate the migration of PV resources, you can deploy an external NFS server on AWS. The server will be provisioned with a public IP, and could be pointed from both locations - source OCP3 and target OCP4 clusters.

Instance creation and NFS server setup is handled by the following command:
- `ansible-playbook nfs_server_deploy.yml`

Once the NFS server running, the next step is to login into the cluster, where you need to provision the PV resources. Create the PV resources on the cluster by running:
- `ansible-playbook nfs_provision_pvs.yml`
This task could be executed on every cluster that will need access to the NFS server.

When you are finished, just run the playbook to destroy NFS AWS instance:
- `ansible-playbook nfs_server_destroy.yml`

## OCP3 agnosticd multinode in AWS

This type of deployment is used in [parallel-base](https://github.com/fusor/mig-ci/blob/master/pipeline/parallel-base.groovy) pipeline, and is used for creation of multinode cluster. To setup a similar environment outside of CI, please refer to the [official](https://github.com/redhat-cop/agnosticd) doc.
- - - -
