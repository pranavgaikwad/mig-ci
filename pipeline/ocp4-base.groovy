// ocp4-base.groovy

// Set Job properties and triggers
properties([disableConcurrentBuilds(),
parameters([
string(defaultValue: 'v4.1', description: 'OCP4 version to deploy', name: 'OCP4_VERSION', trim: false),
string(defaultValue: 'jenkins-ci-ocp4-base', description: 'OCP4 cluster name to deploy', name: 'CLUSTER_NAME', trim: false),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_aws_access_key_id', description: 'EC2 access key ID for auth purposes', name: 'EC2_ACCESS_KEY_ID', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_aws_secret_access_key', description: 'EC2 private key needed to access instances, from Jenkins credentials store', name: 'EC2_SECRET_ACCESS_KEY', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: 'ci_pull_secret', description: 'Pull secret needed for OCP4 deployments', name: 'OCP4_PULL_SECRET', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: 'ci_pub_key', description: 'EC2 public key needed for OCP4 instances', name: 'EC2_PUB_KEY', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.UsernamePasswordMultiBinding', defaultValue: 'ci_ocp4_admin_credentials', description: 'Cluster admin credentials used in OCP4 deployments', name: 'OCP4_CREDENTIALS', required: true),
string(defaultValue: 'ci', description: 'EC2 SSH key name to deploy on instances for remote access ', name: 'EC2_KEY', trim: false),
string(defaultValue: 'eu-west-1', description: 'AWS region to deploy instances', name: 'AWS_REGION', trim: false),
booleanParam(defaultValue: true, description: 'Clean up workspace after build', name: 'CLEAN_WORKSPACE'),
booleanParam(defaultValue: true, description: 'EC2 terminate instances after build', name: 'EC2_TERMINATE_INSTANCES')]),
pipelineTriggers([cron('@midnight')])])

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

        stage('Setup for target cluster') {
            steps_finished << 'Setup for target cluster'

            utils.prepare_workspace('', "${env.OCP4_VERSION}")
            utils.copy_public_keys()
            utils.clone_related_repos()
        }

        common_stages.deployOCP4(TARGET_KUBECONFIG).call()

    } catch (Exception ex) {
        currentBuild.result = "FAILED"
        println(ex.toString())
    } finally {
        // Success or failure, always send notifications
        utils.notifyBuild(currentBuild.result)
        stage('Clean Up Environment') {
        // Success or failure, always terminate instances if requested
            utils.teardown_OCP4()
            if (CLEAN_WORKSPACE) {
                cleanWs cleanWhenFailure: false, notFailBuild: true
            }
        }
	}
}
