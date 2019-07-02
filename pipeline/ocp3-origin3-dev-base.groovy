#!groovy
// ocp-origin3-dev-base.groovy


// Set Job properties and triggers
properties([disableConcurrentBuilds(),
parameters([string(defaultValue: 'v3.11', description: 'OpenShift version to deploy', name: 'OCP3_VERSION', trim: false), 
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_aws_access_key_id', description: 'EC2 access key ID for auth purposes', name: 'EC2_ACCESS_KEY_ID', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_aws_secret_access_key', description: 'EC2 private key needed to access instances, from Jenkins credentials store', name: 'EC2_SECRET_ACCESS_KEY', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: 'ci_ec2_key', description: 'EC2 private key needed to access instances, from Jenkins credentials store', name: 'EC2_PRIV_KEY', required: true),
string(defaultValue: 'ci', description: 'EC2 SSH key name to deploy on instances for remote access ', name: 'EC2_KEY', trim: false),
string(defaultValue: 'eu-west-1', description: 'EC2 region to deploy instances', name: 'AWS_REGION', trim: false),
booleanParam(defaultValue: true, description: 'EC2 terminate instances after build', name: 'EC2_TERMINATE_INSTANCES')]),
[$class: 'ThrottleJobProperty', categories: ['OCP'], limitOneJobWithMatchingParams: true, maxConcurrentPerNode: 0, maxConcurrentTotal: 0, paramsToUseForLimit: '', throttleEnabled: true, throttleOption: 'category']])

// true/false build parameter that defines if we terminate instances once build is done
def EC2_TERMINATE_INSTANCES = params.EC2_TERMINATE_INSTANCES

def common_stages
def utils

steps_finished = []

echo "Running job ${env.JOB_NAME}, build ${env.BUILD_ID} on ${env.JENKINS_URL}"
echo "Build URL ${env.BUILD_URL}"
echo "Job URL ${env.JOB_URL}"

node {
    try {
        checkout scm
        utils = load "${env.WORKSPACE}/pipeline/utils.groovy"
        common_stages = load "${env.WORKSPACE}/pipeline/common_stages.groovy"

        utils.notifyBuild('STARTED')

        stage('Setup Build Environment OCP3') {
            steps_finished << 'Setup Build Environment origin3-dev OCP3 ' + "${OCP3_VERSION}"

            utils.prepare_workspace("${env.OCP3_VERSION}", '')

            utils.copy_private_keys()

            utils.clone_related_repos()

            utils.prepare_origin3_dev()
        }

        common_stages.deploy_origin3_dev(SOURCE_KUBECONFIG).call()

        common_stages.load_sample_data(SOURCE_KUBECONFIG).call()

        common_stages.sanity_checks(SOURCE_KUBECONFIG).call()

    } catch (Exception ex) {
        currentBuild.result = "FAILED"
        println(ex.toString())
    } finally {
    	stage('Clean Up Environment') {
            utils.teardown_origin3_dev()
            cleanWs cleanWhenFailure: false, notFailBuild: true
        }
        // Success or failure, always send notifications
        utils.notifyBuild(currentBuild.result)
    }
}
