// parallel-base.groovy

// Set Job properties and triggers
properties([disableConcurrentBuilds(),
parameters([string(defaultValue: 'v3.11', description: 'OCP3 version to deploy', name: 'OCP3_VERSION', trim: false),
string(defaultValue: 'v4.1', description: 'OCP4 version to deploy', name: 'OCP4_VERSION', trim: false),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_aws_access_key_id', description: 'EC2 access key ID for auth purposes', name: 'EC2_ACCESS_KEY_ID', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_aws_secret_access_key', description: 'EC2 private key needed to access instances, from Jenkins credentials store', name: 'EC2_SECRET_ACCESS_KEY', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: 'ci_ec2_key', description: 'EC2 private key needed to access instances, from Jenkins credentials store', name: 'EC2_PRIV_KEY', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_rhel_sub_user', description: 'RHEL Openshift subscription account username', name: 'EC2_SUB_USER', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_rhel_sub_pass', description: 'RHEL Openshift subscription account password', name: 'EC2_SUB_PASS', required: true),
string(defaultValue: 'ci', description: 'EC2 SSH key name to deploy on instances for remote access ', name: 'EC2_KEY', trim: false),
string(defaultValue: 'eu-west-1', description: 'EC2 region to deploy instances', name: 'EC2_REGION', trim: false),
string(defaultValue: 'm4.large', description: 'EC2 instance type to deploy', name: 'EC2_INSTANCE_TYPE', trim: false),
string(defaultValue: 'jenkins-parallel-ci', description: 'Cluster names to deploy', name: 'CLUSTER_NAME', trim: false),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: 'ci_pull_secret', description: 'Pull secret needed for OCP4 deployments', name: 'OCP4_PULL_SECRET', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: 'ci_pub_key', description: 'EC2 public key needed for OCP4 instances', name: 'EC2_PUB_KEY', required: true),
booleanParam(defaultValue: true, description: 'Clean up workspace after build', name: 'CLEAN_WORKSPACE'),
booleanParam(defaultValue: true, description: 'EC2 terminate instances after build', name: 'EC2_TERMINATE_INSTANCES')])])

// true/false build parameter that defines if we terminate instances once build is done
def EC2_TERMINATE_INSTANCES = params.EC2_TERMINATE_INSTANCES
// true/false build parameter that defines if we cleanup workspace once build is done
def CLEAN_WORKSPACE = params.CLEAN_WORKSPACE

def common_stages
def utils

steps_finished = []

echo "Running job ${env.JOB_NAME}, build ${env.BUILD_ID} on ${env.JENKINS_URL}"
echo "Build URL ${env.BUILD_URL}"
echo "Job URL ${env.JOB_URL}"

node {
    try {
        checkout scm
        common_stages = load "${env.WORKSPACE}/pipeline/common_stages.groovy"
        utils = load "${env.WORKSPACE}/pipeline/utils.groovy"

        utils.notifyBuild('STARTED')

        stage('Setup for source and target cluster') {
            steps_finished << 'Setup for source and target cluster'

            utils.prepare_workspace("${env.OCP3_VERSION}", "${env.OCP4_VERSION}")
            utils.copy_private_keys()
            utils.copy_public_keys()
            utils.clone_related_repos()
        }

        stage('Deploy clusters') {
            steps_finished << 'Deploy clusters'
            parallel deploy_OCP3: {
                common_stages.deployOCP3_OA("prefix=${env.CLUSTER_NAME}").call()
            }, deploy_OCP4: {
                common_stages.deployOCP4().call()
            }, deploy_NFS: {
                common_stages.deploy_NFS().call()
            }
            failFast: true
        }

        stage('Provison PVs on source cluster') {
            steps_finished << 'Provison PVs on source cluster'
            withCredentials([
                string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
                string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY')
                ]) 
            {
                
                withEnv(['PATH+EXTRA=~/bin', "KUBECONFIG=${KUBECONFIG_OCP3}"]) {
                    ansiColor('xterm') {
                        ansiblePlaybook(
                            playbook: 'nfs_provision_pvs.yml',
                            hostKeyChecking: false,
                            unbuffered: true,
                            colorized: true)
                    }
                }
            }
        }

        stage('Deploy mig-controller and mig-ui on source cluster') {
            steps_finished << 'Deploy mig-controller and mig-ui on source cluster'
            withEnv(['PATH+EXTRA=~/bin', "KUBECONFIG=${KUBECONFIG_OCP3}"]) {
                ansiColor('xterm') {
                    ansiblePlaybook(
                        playbook: 'mig_controller_deploy.yml',
                        hostKeyChecking: false,
                        unbuffered: true,
                        colorized: true)
                }
            }
        }


        stage('Deploy mig-controller on target cluster') {
            steps_finished << 'Deploy mig-controller on target cluster'
            withCredentials([
                string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
                string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY')
                ]) 
            {
                withEnv(['PATH+EXTRA=~/bin', "KUBECONFIG=${KUBECONFIG_OCP4}"]) {
                    ansiColor('xterm') {
                        ansiblePlaybook(
                            playbook: 'mig_controller_deploy.yml',
                            extras: "-e with_controller=false",
                            hostKeyChecking: false,
                            unbuffered: true,
                            colorized: true)
                    }
                }
            }
        }

        stage('Prepare test data on source cluster') {
        steps_finished << 'Prepare test data on source cluster'
            withEnv(['PATH+EXTRA=~/bin', "KUBECONFIG=${KUBECONFIG_OCP3}"]) {
                dir('ocp-mig-test-data') {
                    ansiColor('xterm') {
                        ansiblePlaybook(
                            playbook: 'mysql-pvc.yml',
                            extras: "-e with_backup=false -e with_restore=false",
                            hostKeyChecking: false,
                            unbuffered: true,
                            colorized: true)
                    }
                }
            }
        }

        stage('Execute migration') {
            steps_finished << 'Execute migration'
            // TODO
        }

        stage('Verify migration sanity') {
            steps_finished << 'Verify migration sanity'
            // TODO
        }

    } catch (e) {
        currentBuild.result = "FAILED"
        throw e
    } finally {
        // Success or failure, always send notifications
        utils.notifyBuild(currentBuild.result)
        stage('Clean Up Environment') {
            // Always attempt to terminate instances if EC2_TERMINATE_INSTANCES is true
            if (EC2_TERMINATE_INSTANCES) {
                        withCredentials([
                            string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
                            string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY')
                            ]) 
                        {
                            withEnv(['PATH+EXTRA=~/bin', "AWS_REGION=${EC2_REGION}"]) {
                                parallel destroy_OCP3: {
                                    common_stages.teardown_OCP3_OA("prefix=${env.CLUSTER_NAME}")
                                }, destroy_OCP4: {
                                    common_stages.teardown_OCP4()
                                }, destroy_NFS: {
                                    ansiColor('xterm') {
                                            ansiblePlaybook(
                                                playbook: 'nfs_server_destroy.yml',
                                                hostKeyChecking: false,
                                                unbuffered: true,
                                                colorized: true)
                                    }
                                }, failFast: false
                            }
                        }
                }
            if (CLEAN_WORKSPACE) {
                cleanWs cleanWhenFailure: false, notFailBuild: true
            }
        }
    }
}
