// mig-e2e-base.groovy
properties([
parameters([choice(choices: ['3.7', '3.9', '3.10', '3.11', '4.1', '4.2', 'nightly'], description: 'Source cluster version to test', name: 'SRC_CLUSTER_VERSION'),
choice(choices: ['4.2', '3.11', '3.10', '3.9', '3.7', '4.1', 'nightly'], description: 'Destination cluster version to test', name: 'DEST_CLUSTER_VERSION'),
string(defaultValue: '', description: 'Source cluster API endpoint', name: 'SRC_CLUSTER_URL', trim: false, required: true),
string(defaultValue: '', description: 'Destination cluster API endpoint', name: 'DEST_CLUSTER_URL', trim: false, required: true),
string(defaultValue: '', description: 'AWS region where clusters are deployed', name: 'AWS_REGION', trim: false, required: true),
string(defaultValue: 'https://github.com/fusor/mig-operator.git', description: 'Mig operator repo to clone', name: 'MIG_OPERATOR_REPO', trim: false),
string(defaultValue: 'master', description: 'Mig operator branch to test', name: 'MIG_OPERATOR_BRANCH', trim: false),
string(defaultValue: 'https://github.com/fusor/mig-controller.git', description: 'Mig controller repo to test, only used by GHPRB', name: 'MIG_CONTROLLER_REPO', trim: false),
string(defaultValue: 'master', description: 'Mig controller repo branch to test', name: 'MIG_CONTROLLER_BRANCH', trim: false),
string(defaultValue: 'https://github.com/fusor/mig-e2e.git', description: 'Mig e2e repo to test', name: 'MIG_E2E_REPO', trim: false),
string(defaultValue: 'master', description: 'Mig e2e repo branch to test', name: 'MIG_E2E_BRANCH', trim: false),
string(defaultValue: 'e2e_mig_samples.yml', description: 'e2e test playbook to run, see https://github.com/fusor/mig-e2e for details', name: 'E2E_PLAY', trim: false),
string(defaultValue: 'all', description: 'e2e test tags to run, see https://github.com/fusor/mig-e2e for details, space delimited', name: 'E2E_TESTS', trim: false),
string(defaultValue: 'latest', description: 'Mig Operator/CAM release to deploy', name: 'MIG_OPERATOR_RELEASE', trim: false),
string(defaultValue: 'scripts/mig_debug.sh', description: 'Relative file path to debug script on MIG CI repo', name: 'DEBUG_SCRIPT', trim: false),
string(defaultValue: '', description: 'Extra debug script arguments', name: 'DEBUG_SCRIPT_ARGS', trim: false),
string(defaultValue: 'quay.io/fbladilo/mig-controller', description: 'Repo for quay io for custom mig-controller images, only used by GHPRB', name: 'QUAYIO_CI_REPO', trim: false),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.UsernamePasswordMultiBinding', defaultValue: 'ci_quay_credentials', description: 'Credentials for quay.io container storage, used by mig-controller to push and pull images', name: 'QUAYIO_CREDENTIALS', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_aws_access_key_id', description: 'EC2 access key ID for auth purposes', name: 'EC2_ACCESS_KEY_ID', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_aws_secret_access_key', description: 'EC2 private key needed to access instances, from Jenkins credentials store', name: 'EC2_SECRET_ACCESS_KEY', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.UsernamePasswordMultiBinding', defaultValue: 'ci_ocp4_admin_credentials', description: 'Cluster admin credentials used in OCP4 deployments', name: 'OCP4_CREDENTIALS', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.UsernamePasswordMultiBinding', defaultValue: 'ci_ocp3_admin_credentials', description: 'Cluster admin credentials used in OCP3 deployments', name: 'OCP3_CREDENTIALS', required: true),
booleanParam(defaultValue: true, description: 'Deploy mig operator using OLM on OCP4', name: 'USE_OLM'),
booleanParam(defaultValue: false, description: 'Deploy using downstream images', name: 'USE_DOWNSTREAM'),
booleanParam(defaultValue: false, description: 'Enable debugging', name: 'DEBUG'),
booleanParam(defaultValue: true, description: 'Clean up workspace after build', name: 'CLEAN_WORKSPACE')])])


// true/false build parameter that defines if we use OLM to deploy mig operator on OCP4
USE_OLM = params.USE_OLM
// true/false build parameter that defines if we cleanup workspace once build is done
def CLEAN_WORKSPACE = params.CLEAN_WORKSPACE
// Split e2e tests from string param
def E2E_TESTS = params.E2E_TESTS.split(' ')
// true/false enable debugging
def DEBUG = params.DEBUG
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
        common_stages = load "${WORKSPACE}/pipeline/common_stages.groovy"
        utils = load "${WORKSPACE}/pipeline/utils.groovy"

        utils.notifyBuild('STARTED')

        stage('Setup e2e environment') {
            steps_finished << 'Setup e2e environment'

            utils.prepare_workspace(SRC_CLUSTER_VERSION, DEST_CLUSTER_VERSION)
            utils.clone_mig_e2e()
            if (env.MIG_CONTROLLER_REPO != 'https://github.com/fusor/mig-controller.git') {
              utils.clone_mig_controller()
            }
        }

        withCredentials([
          [$class: 'UsernamePasswordMultiBinding', credentialsId: "${OCP3_CREDENTIALS}", usernameVariable: 'OCP3_ADMIN_USER', passwordVariable: 'OCP3_ADMIN_PASSWD'],
          [$class: 'UsernamePasswordMultiBinding', credentialsId: "${OCP4_CREDENTIALS}", usernameVariable: 'OCP4_ADMIN_USER', passwordVariable: 'OCP4_ADMIN_PASSWD']
          ]) {
              common_stages.login_cluster("${SRC_CLUSTER_URL}", "${OCP3_ADMIN_USER}", "${OCP3_ADMIN_PASSWD}", "${SRC_CLUSTER_VERSION}", SOURCE_KUBECONFIG).call()
              common_stages.login_cluster("${DEST_CLUSTER_URL}", "${OCP4_ADMIN_USER}", "${OCP4_ADMIN_PASSWD}", "${DEST_CLUSTER_VERSION}", TARGET_KUBECONFIG).call()
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
	if (DEBUG) {
          utils.run_debug(SOURCE_KUBECONFIG)
          utils.run_debug(TARGET_KUBECONFIG)
	}
        stage('Clean Up Environment') {
          // Always attempt to remove s3 buckets
          utils.teardown_s3_bucket()
          if (CLEAN_WORKSPACE) {
              cleanWs notFailBuild: true
          }
        }
      }
    }
}
