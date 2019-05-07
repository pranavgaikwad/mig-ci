
# OCP MIG CI

  

## Purpose

  

This repo contains ansible and CI related assets used for the OCP 3 to 4 migration project, the primary use case for these tools is to integrate with Jenkins and allow the creation of all necessary CI workflows.

  

## OCP3 all-in-one deployment on AWS

  

In order to execute an all-in-one deployment, firstly you should specify several environment variables.

  

RHEL subscriptions:

- SUB_USER -- redhat subscription username for account, which have access to the openshift bits.

- SUB_PASS -- password for the redhat subscriprion account.

  

Steps to reproduce the deployment in CI:

* Setup all the environment variables, including this task specific.

* Get the `openshift-ansible` repo by `ansible-playbook get-openshift-ansible.yml`. This playbook will clone the repo into the `$WORKSPACE/openshift-ansible`, and checkout the selected OA release from 3.7 to 3.11.

* Deploy the EC2 instance and the all-in-one configuration by running `ansible-playbook deploy_ocp3_cluster.yml`.

* To destroy the EC2 instance, run `ansible-playbook destroy_ocp3_cluster.yml`.

  

## List of other environment variables:

- `AWS_ACCESS_KEY_ID` -- AWS access key id, which is used to access your AWS environment.

- `AWS_SECRET_ACCESS_KEY` -- secret, used for authentication.

- `WORKSPACE` -- default value is `../`. Should specify a placement of workspace, which contains the `keys` directory, with all the ssh private and public keys used during the run are located. The name of the key is specified by the `EC2_KEY` variable.

- `EC2_KEY` -- name of the ssh private key, which will be passed to the instance, and allow you to access the instance via ssh in future. Set to `ci` by default. The key should be discoverable with `${WORKSPACE}/keys/${EC2_KEY}.pem`.

- `EC2_REGION` -- region, where all resources will be created. By default is set to `us-east-1`.

- `CLUSTER_VERSION` -- CI is deciding, which cluster to provision, based on this variable. Default value is 4, or could be set to 3.

- `CLUSTER_NAME` -- this variable is used in multiple location. In this scenario it's perpouse, is to specify prefix for a newly created AWS EC2 instance. When it is not specified, your ansible username will be used. All EC2 instances are named by the following convention: `$CLUSTER_NAME-aws-ocp-3.(7-11)`.

- - - -
