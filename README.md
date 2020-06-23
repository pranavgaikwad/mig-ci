
# OCP MIG CI

## Purpose

This repo contains ansible and CI related assets used for the OCP 3 to 4 migration project, the primary use case for these tools is to integrate with Jenkins and allow the creation of all necessary CI workflows.

## Pipelines 

Jenkins _pipelines_ are used to provide the logic necessary to orchestrate the build and execution of CI workflows. Each pipeline job can be parameterized to customize the behavior for the intended workflow, they are also responsible of providing notifications for each build, below are the supplied  [mig CI pipelines](https://github.com/konveyor/mig-ci/tree/master/pipeline) with a brief description:

| Pipeline | Purpose |
| --- | --- |
| `ocp3-base` | Deploys OCP3 using [mig-agnosticd](https://github.com/konveyor/mig-agnosticd/tree/master/3.x) |
| `ocp4-base` | Deploys OCP4 using [mig-agnosticd](https://github.com/konveyor/mig-agnosticd/tree/master/4.x) |
| `parallel-base` | Deploys OCP3, OCP4 in parallel, installs cluster application migration tools and executes e2e migration tests |
| `mig-e2e-base` | Installs cluster application migration toolkit and executes e2e migration tests |

### CI job logic

The use of _**trigger jobs**_ which are parameterized is key to the structure of the CI workflows, trigger jobs can for instance watch repositories for activity and execute _**base pipelines**_ to run a CI workflow. A good example are trigger jobs watching the [mig-controller repo](https://github.com/konveyor/mig-controller) for changes and executing the [parallel base pipeline](https://github.com/konveyor/mig-ci/blob/master/pipeline/parallel-base.groovy) with [e2e tests](https://github.com/konveyor/mig-e2e)

### CI job parameters

Below are some of the most commonly used parameters allowing the customization of mig CI jobs :

| Parameter | Purpose | Notes |
| --- | --- | --- |
| `AWS_REGION` | AWS region for resources to deploy | Default varies based on pipeline |
| `SRC_CLUSTER_VERSION`| OCP source cluster version to deploy | Default is v3.7 |
| `DEST_CLUSTER_VERSION`| OCP destination cluster version to deploy | Default is v4.3 |
| `OCP3_WORKER_INSTANCE_COUNT` | Number of OCP3 compute nodes to create |
| `OCP3_MASTER_INSTANCE_COUNT` | Number of OCP3 master nodes to create |
| `OCP4_WORKER_INSTANCE_COUNT` | Number of OCP4 compute nodes to create |
| `OCP4_MASTER_INSTANCE_COUNT` | Number of OCP4 master nodes to create |
| `CLUSTER_NAME` | Name of the cluster to deploy | The final deployment will use the following convention: `${CLUSTER_NAME}-<version>-${BUILD_NUMBER}`. In AWS you can use this value on instance tags GUID label|
| `EC2_KEY` | Name of SSH public and private key | Default is `ci`. Will be used to allow SSH access to instances |
| `MIG_OPERATOR_REPO` | Repository for mig-operator to test | Default is konveyor |
| `MIG_OPERATOR_BRANCH` | Branch for mig-operator to test | Default is master |
| `MIG_E2E_REPO` | Repository for e2e tests| Default is [mig-e2e](https://github.com/konveyor/mig-e2e) |
| `MIG_E2E_REPO_BRANCH` | Repository for e2e tests | Default is master |
| `SUB_USER` | RH subscription username | Only used in OA deployments to access OCP bits |
| `SUB_PASS` | RH subscription password | Only used in OA deployments to access OCP bits |
| `CLEAN_WORKSPACE` | Clean Jenkins workspace after build | Default is true |
| `E2E_RUN` | Run end-to-end tests after deployment | Default is true |
| `EC2_TERMINATE_INSTANCES` | Terminate all instances on EC2 after build | Default is true |
| `EC2_TERMINATE_INSTANCES` | Terminate all instances on EC2 after build | Default is true |
| `DEBUG` | Enable debugging at the end of the job | Default is false |


_**Note:**_ **For a full list of all possible parameters please inspect each pipeline script**

### Migration controller e2e tests

The migration controller e2e tests are supplied in the [mig-e2e repo](https://github.com/konveyor/mig-e2e), the tests are based on [mig controller sample scenarios](https://github.com/konveyor/mig-controller/tree/master/docs/scenarios) and are executed during the last stage of CI jobs.

### Debugging CI jobs

Check out our [Debug Guide](./DEBUG-GUIDE.md) for detailed instructions on debugging CI jobs.

## External NFS server setup on AWS

In order to demonstrate the migration of PV resources, you can deploy an external NFS server on AWS. The server will be provisioned with a public IP, and could be pointed from both locations - source OCP3 and target OCP4 clusters.

Instance creation and NFS server setup is handled by the following command:
- `ansible-playbook nfs_server_deploy.yml`

Once the NFS server running, the next step is to login into the cluster, where you need to provision the PV resources. Create the PV resources on the cluster by running:
- `ansible-playbook nfs_provision_pvs.yml`
This task could be executed on every cluster that will need access to the NFS server.

When you are finished, just run the playbook to destroy NFS AWS instance:
- `ansible-playbook nfs_server_destroy.yml`

- - - -
