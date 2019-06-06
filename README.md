
# OCP MIG CI

## Purpose

This repo contains ansible and CI related assets used for the OCP 3 to 4 migration project, the primary use case for these tools is to integrate with Jenkins and allow the creation of all necessary CI workflows. It will consist of several ansible playbooks, which will prepare a environment for migration purposes. This involves provisioning customer-like cluster deployments with [OA installer](https://github.com/openshift/openshift-ansible), and setup of the migration tools from [mig-controller](https://github.com/fusor/mig-controller).

## External NFS server setup on AWS

In order to demonstrate the `swing` migration of the PV resources, wich will be based on NFS, you can deploy an external NFS server on AWS. The server will have a public IP, and could be pointed from both locations - source OCP3 and target OCP4 based clusters.

Instance creation and NFS server setup is handled by the following command:
- `ansible-playbook nfs_server_deploy.yml`

From that moment you have the NFS server running. The next step is to login into the cluster, where you need to provision the PV resources. Then you are ready to create the PV resources on the cluster by running:
- `ansible-playbook nfs_provision_pvs.yml`
This task could be executed repetitively, for every cluster you need to bond with the NFS server.

When you are finished, just run the playbook to destroy NFS AWS instance:
- `ansible-playbook nfs_server_destroy.yml`

Functionality of this tool is tested with both [all-in-one](https://github.com/fusor/mig-ci#ocp3-all-in-one-deployment-on-aws) AWS deployment, and [origin3-dev](https://github.com/fusor/origin3-dev/).

## Migration controller CI deployment

Source: https://github.com/fusor/mig-controller

(TODO)

## OCP3 all-in-one deployment on AWS

In order to execute an all-in-one deployment, firstly you should specify several environment variables.

Customer environment is expected to be based on RHEL distributions. By default the RHEL 7 does not have an access to the [official OpenShift bits](https://docs.openshift.com/enterprise/3.0/install_config/install/prerequisites.html#software-prerequisites) for the OA deployment, so you need to setup a valid account with those subscriptions by following variables:

Task specific:

- `AWS_ACCESS_KEY_ID` - AWS access key id for AWS deployment.

- `AWS_SECRET_ACCESS_KEY` - secret, used for AWS deployment.

- `SUB_USER` - redhat subscription username for account, which have access to the openshift bits. Allows to setup an `enterprise` and `origin` OA deployment.

- `SUB_PASS` - password for the redhat subscriprion account.

- `OCP3_VERSION` - verison of cluster to be provisioned. Should be specified as 'v3\.[0-9]+'. If not set, will be used 'v3.11'.

- `WORKSPACE` - from [other variables](https://github.com/fusor/mig-ci#list-of-other-environment-variables). You should create a directory `$WORKSPACE/keys` and place there your AWS ssh private key, which will be supplied to the newly created instance. The name of the key is captured from `$EC2_KEY`.
  - The result ssh private key file will should be discoverable in `${WORKSPACE}/keys/${EC2_KEY}.pem`. You can use it after that, to access the instance via ssh.

Deployment steps:

- Setup all of the [environment variables](https://github.com/fusor/mig-ci#list-of-other-environment-variables), including specific for this task, mentioned in the previous paragraph.

- To deploy an OA `ansible-playbook deploy_ocp3_cluster.yml -e prefix=name_for_instance`. You can specify deployment type with `-e deployment_type=openshift-enterprise/origin`. Default is the enterprise one. When prefix is not specified, you `ansible_user` variable will be used.

If you want to select the downstream version of openshift, you can add `-e deployment_type=origin` tag to previous step.

- To destroy the instance and all attached resources `ansible-playbook destroy_ocp3_cluster.yml -e prefix=name_for_instance`.

Deployment usually takes around 40-80 minutes to complete. Logs are written in `$WORKSPACE/.install.log` file upon completion.

## List of other environment variables:

- `AWS_ACCESS_KEY_ID` - AWS access key id, which is used to access your AWS environment.

- `AWS_SECRET_ACCESS_KEY` - secret, used for authentication.

- `WORKSPACE` - Should specify a placement of workspace, which contains the `keys` directory, with all the ssh private and public keys used during the run are located. The name of the key is specified by the `EC2_KEY` variable. If was not specified, will be used the default value - `../`.

- `EC2_KEY` - name of the ssh private key, which will be passed to the instance, and allow you to access the instance via ssh in future. Set to `ci` by default. The key should be discoverable with `${WORKSPACE}/keys/${EC2_KEY}.pem`.

- `EC2_REGION` - region, where all resources will be created.

- `CLUSTER_VERSION` - CI is deciding, which cluster to provision, based on this variable. Default value is 4, or could be set to 3.

- `CLUSTER_NAME` - this variable is used in multiple location. In this scenario it's perpouse, is to specify prefix for a newly created AWS EC2 instance. When it is not specified, your ansible username will be used. All EC2 instances are named by the following convention: `$CLUSTER_NAME-<instance role>-3.(7-11)`.

- `KUBECONFIG` - if this envrironment variable is set, then the `oc` binary will use the configuration file from there to perform any operations on cluster. By default the `~/.kube/config` location is used. https://docs.openshift.com/container-platform/3.11/cli_reference/manage_cli_profiles.html

- - - -
