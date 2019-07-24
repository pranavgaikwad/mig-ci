// parallel-base.groovy

// Set Job properties and triggers
properties([
parameters([string(defaultValue: 'v3.11', description: 'OCP3 version to test', name: 'OCP3_VERSION', trim: false),
string(defaultValue: 'v4.1', description: 'OCP4 version to test', name: 'OCP4_VERSION', trim: false),
string(defaultValue: '', description: 'OCP3 source cluster API endpoint', name: 'OCP3_CLUSTER_URL', trim: false, required: true),
string(defaultValue: '', description: 'OCP4 destination cluster API endpoint', name: 'OCP4_CLUSTER_URL', trim: false, required: true),
string(defaultValue: 'eu-west-1', description: 'AWS region where clusters are deployed', name: 'AWS_REGION', trim: false),
string(defaultValue: 'https://github.com/fusor/mig-controller.git', description: 'Mig controller repo to test', name: 'MIG_CONTROLLER_REPO', trim: false),
string(defaultValue: 'master', description: 'Mig controller repo branch to test', name: 'MIG_CONTROLLER_BRANCH', trim: false),
string(defaultValue: 'https://github.com/fusor/mig-e2e.git', description: 'Mig e2e repo to test', name: 'MIG_E2E_REPO', trim: false),
string(defaultValue: 'master', description: 'Mig e2e repo branch to test', name: 'MIG_E2E_BRANCH', trim: false),
string(defaultValue: 'all', description: 'e2e test tags to run, see https://github.com/fusor/mig-e2e for details, space delimited', name: 'E2E_TESTS', trim: false),
string(defaultValue: 'quay.io/fbladilo/mig-controller', description: 'Repo for quay io mig-controller images', name: 'QUAYIO_CI_REPO', trim: false),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.UsernamePasswordMultiBinding', defaultValue: 'ci_quay_credentials', description: 'Credentials for quay.io container storage, used by mig-controller to push and pull images', name: 'QUAYIO_CREDENTIALS', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_aws_access_key_id', description: 'EC2 access key ID for auth purposes', name: 'EC2_ACCESS_KEY_ID', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_aws_secret_access_key', description: 'EC2 private key needed to access instances, from Jenkins credentials store', name: 'EC2_SECRET_ACCESS_KEY', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.UsernamePasswordMultiBinding', defaultValue: 'ci_ocp4_admin_credentials', description: 'Cluster admin credentials used in OCP4 deployments', name: 'OCP4_CREDENTIALS', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.UsernamePasswordMultiBinding', defaultValue: 'ci_ocp3_admin_credentials', description: 'Cluster admin credentials used in OCP3 deployments', name: 'OCP3_CREDENTIALS', required: true),
booleanParam(defaultValue: true, description: 'Clean up workspace after build', name: 'CLEAN_WORKSPACE')])])

// true/false build parameter that defines if we cleanup workspace once build is done
def CLEAN_WORKSPACE = params.CLEAN_WORKSPACE
// Split e2e tests from string param
def E2E_TESTS = params.E2E_TESTS.split(' ')

def common_stages
def utils

steps_finished = []

echo "Running job ${env.JOB_NAME}, build ${env.BUILD_ID} on ${env.JENKINS_URL}"
echo "Build URL ${env.BUILD_URL}"
echo "Job URL ${env.JOB_URL}"

node {
    sh "mkdir ${WORKSPACE}-${BUILD_NUMBER}"
    ws("${WORKSPACE}-${BUILD_NUMBER}") {
    try {
        checkout scm
        common_stages = load "${env.WORKSPACE}/pipeline/common_stages.groovy"
        utils = load "${env.WORKSPACE}/pipeline/utils.groovy"

        utils.notifyBuild('STARTED')

        stage('Setup e2e environment') {
            steps_finished << 'Setup e2e environment'

            utils.prepare_workspace("${env.OCP3_VERSION}", "${env.OCP4_VERSION}")
            utils.clone_mig_e2e()
            utils.clone_mig_controller()
        }

        withCredentials([
          [$class: 'UsernamePasswordMultiBinding', credentialsId: "${OCP3_CREDENTIALS}", usernameVariable: 'OCP3_ADMIN_USER', passwordVariable: 'OCP3_ADMIN_PASSWD'],
          [$class: 'UsernamePasswordMultiBinding', credentialsId: "${OCP4_CREDENTIALS}", usernameVariable: 'OCP4_ADMIN_USER', passwordVariable: 'OCP4_ADMIN_PASSWD']
          ]) {
              common_stages.login_cluster("${env.OCP3_CLUSTER_URL}", "${env.OCP3_ADMIN_USER}", "${env.OCP3_ADMIN_PASSWD}", "${env.OCP3_VERSION}", SOURCE_KUBECONFIG).call()
              common_stages.login_cluster("${env.OCP4_CLUSTER_URL}", "${env.OCP4_ADMIN_USER}", "${env.OCP4_ADMIN_PASSWD}", "${env.OCP4_VERSION}", TARGET_KUBECONFIG).call()
             }
        // Always ensure mig controller environment is clean before deployment
        utils.teardown_mig_controller(SOURCE_KUBECONFIG)
        utils.teardown_mig_controller(TARGET_KUBECONFIG)

        // Deploy mig controller and begin tests
        common_stages.deploy_mig_controller_on_both(SOURCE_KUBECONFIG, TARGET_KUBECONFIG, false, true).call()
        common_stages.execute_migration(E2E_TESTS, SOURCE_KUBECONFIG, TARGET_KUBECONFIG).call()

    } catch (Exception ex) {
        currentBuild.result = "FAILED"
        println(ex.toString())
    } finally {
        // Success or failure, always send notifications
        utils.notifyBuild(currentBuild.result)
        stage('Clean Up Environment') {
          if (CLEAN_WORKSPACE) {
              cleanWs cleanWhenFailure: false, notFailBuild: true
          }
        }
      }
    }
}
