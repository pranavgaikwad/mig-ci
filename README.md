
# OCP MIG CI

## Purpose

This repo contains ansible and CI related assets used for the OCP 3 to 4 migration project, the primary use case for these tools is to integrate with Jenkins and allow the creation of all necessary CI workflows.

## Pipelines 

Jenkins _pipelines_ are used to provide the logic necessary to orchestrate the build and execution of CI workflows. Each pipeline job can be parameterized to customize the behavior for the intended workflow, they are also responsible of providing notifications for each build, below are the supplied  [mig CI pipelines](https://github.com/fusor/mig-ci/tree/master/pipeline) with a brief description:

| Pipeline | Purpose |
| --- | --- |
| `ocp3-agnosticd-base` | Deploys OCP3 using [agnosticd](https://github.com/fbladilo/testing#ocp3-agnosticd-multinode-in-aws), performs cluster sanity checks, multi-node cluster support |
| `ocp3-oa-base` | Deploys OCP3 using [openshift ansible](https://github.com/fbladilo/testing#ocp3-oa-all-in-one-deployment-on-aws), performs cluster sanity checks, all-in-one cluster |
| `ocp3-origin3-dev-base` | Deploys OCP3 using [origin3-dev](https://github.com/fusor/origin3-dev.git), all-in-one cluster |
| `ocp4-base` | Deploys OCP4 and performs cluster sanity checks |
| `parallel-base` | Deploys OCP3, OCP4, NFS server in parallel, installs cluster application migration tools and executes e2e migration tests|
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

## OCP3 OA all-in-one deployment on AWS

In order to execute an OCP3 all-in-one deployment, you need to supply SSH keys and define several environmental variables.

Pre-requirements :

- `WORKSPACE` - from [other variables](https://github.com/fusor/mig-ci#list-of-other-environment-variables).
  - Create a directory in `$WORKSPACE/keys` and save your AWS SSH private key, it will be used to access the newly created instance. The name of the key is captured from `$EC2_KEY`.
  - The resulting SSH private key file should be accessible in `${WORKSPACE}/keys/${EC2_KEY}.pem`.

### Deploying OA cluster outside of CI :

- Define *required* [environment variables](https://github.com/fusor/mig-ci#list-of-other-environment-variables), please ensure _pre-requirements_ are satisfied.

- To deploy an OA `ansible-playbook deploy_ocp3_cluster.yml -e prefix=name_for_instance`. You can specify deployment type with `-e oa_deployment_type=openshift-enterprise/origin`. Default is the enterprise one. When prefix is not specified, you `ansible_user` variable will be used.

If you want to select the upstream version of openshift, you can add `-e oa_deployment_type=origin` to the previous step.

- To destroy the instance and all attached resources `ansible-playbook destroy_ocp3_cluster.yml -e prefix=name_for_instance`.

Deployment usually takes around 40-80 minutes to complete. Logs are written in `$WORKSPACE/.install.log` file upon completion.

In case that you don't want to set environment variables for every `deploy*` and `destroy*`, the playbook could be run with an external vars file located in `/config/adhoc_vars.yml`.

Example:

- `ansible-playbook deploy_ocp3_cluster.yml -e @config/adhoc_vars.yml`
- `ansible-playbook destroy_ocp3_cluster.yml -e @config/adhoc_vars.yml`

_**Note:**_ By default the RHEL7 does not have an access to the [official OpenShift bits](https://docs.openshift.com/enterprise/3.0/install_config/install/prerequisites.html#software-prerequisites) for the OA deployment. You must supply a RH account with a subscription that has access to the OCP official bits.

### List of other environment variables:

- `OCP3_VERSION` - version of cluster to be provisioned. Should be specified as 'v3\.[0-9]+'. If not set, will be used 'v3.11'.

- `SUB_USER` - redhat subscription username for account, which have access to the openshift bits. Allows to setup an `enterprise` and `origin` OA deployment.

- `SUB_PASS` - password for the redhat subscription account.

- `AWS_REGION` - AWS region, in which all resources will be created.

- `AWS_ACCESS_KEY_ID` - AWS access key id, which is used to access your AWS environment.

- `AWS_SECRET_ACCESS_KEY` - AWS secret access key, used for authentication.

- `WORKSPACE` - location of workspace directory. If not set, the default value is the directory where the playbook is run.

- `EC2_KEY` - name of the SSH private key, which will be passed to the instance, and allow you to access the deployed instance via ssh. Set to `ci` by default. The key should be accessible at `${WORKSPACE}/keys/${EC2_KEY}.pem`.

- `CLUSTER_NAME` - prefix for a newly created AWS EC2 instance. When not specified, your ansible username will be used. All EC2 instances are named by the following convention: `$CLUSTER_NAME-<instance role>-3.(7-11)`.

- `KUBECONFIG` - *optional*, if this environment variable is set, then the `oc` binary will use the configuration file from there to perform any operations on cluster. By default the `~/.kube/config` is used. https://docs.openshift.com/container-platform/3.11/cli_reference/manage_cli_profiles.html

- - - -
