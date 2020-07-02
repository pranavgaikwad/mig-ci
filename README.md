# Konveyor MIG CI

## Purpose

This repo contains ansible and CI related assets used for the Konveyor OCP 3 to 4 migration project, the primary use case for these tools is to integrate with Jenkins and allow the creation of all necessary CI workflows.

## Pipelines 

Jenkins _pipelines_ are used to provide the logic necessary to orchestrate the build and execution of CI workflows. Each pipeline job can be parameterized to customize the behavior for the intended workflow, they are also responsible of providing notifications for each build, below are the supplied  [mig CI pipelines](https://github.com/konveyor/mig-ci/tree/master/pipeline) with a brief description:

| Pipeline | Purpose |
| --- | --- |
| `ocp3-base` | Deploys OCP3 using [mig-agnosticd](https://github.com/konveyor/mig-agnosticd/tree/master/3.x) |
| `ocp4-base` | Deploys OCP4 using [mig-agnosticd](https://github.com/konveyor/mig-agnosticd/tree/master/4.x) |
| `parallel-base` | Deploys OCP3, OCP4 in parallel, installs cluster application migration tools and executes e2e migration tests |
| `mig-e2e-base` | Installs cluster application migration toolkit and executes e2e migration tests |
| `mig-controller-pr-builder` | Used for PR gating on mig-controller repo |

### CI job logic

The use of _**trigger jobs**_ which are parameterized is key to the structure of the CI workflows, trigger jobs can for instance watch repositories for activity and execute _**base pipelines**_ to run a CI workflow. A good example are trigger jobs watching the [mig-controller repo](https://github.com/konveyor/mig-controller) for changes and executing the [parallel base pipeline](https://github.com/konveyor/mig-ci/blob/master/pipeline/parallel-base.groovy) with [e2e tests](https://github.com/konveyor/mig-e2e)

### CI ansible roles

The primary purpose of these roles is to help prepare the cluster environment for e2e migrations, the ansible playbooks/roles are called from within Jenkins _pipelines_ to perform a desired task during a pipeline _stage_ , for example [mig-controller deployment](https://github.com/konveyor/mig-ci/blob/master/mig_controller_deploy.yml)

### Migration controller e2e tests

The migration controller e2e tests are supplied in the [mig-e2e repo](https://github.com/konveyor/mig-e2e), these tests are designed to exercise different features of the migration controller and are executed during the last stage of CI jobs.

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
