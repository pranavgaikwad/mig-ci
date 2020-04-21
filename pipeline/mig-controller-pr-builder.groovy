// PR Builder Pipeline
// All variables required 

// string params 
def SRC_CLUSTER_VERSION = params.SRC_CLUSTER_VERSION
def DEST_CLUSTER_VERSION = params.DEST_CLUSTER_VERSION
def SRC_CLUSTER_URL = params.SRC_CLUSTER_URL
def DEST_CLUSTER_URL = params.DEST_CLUSTER_URL
def AWS_REGION = params.AWS_REGION
def MIG_OPERATOR_REPO = params.MIG_OPERATOR_REPO
def MIG_OPERATOR_BRANCH = params.MIG_OPERATOR_BRANCH
def MIG_CONTROLLER_REPO = params.MIG_CONTROLLER_REPO
def MIG_CONTROLLER_BRANCH = params.MIG_CONTROLLER_BRANCH
def MIG_E2E_REPO = params.MIG_E2E_REPO
def MIG_E2E_BRANCH = params.MIG_E2E_BRANCH
def E2E_PLAY = params.E2E_PLAY
// Split e2e tests from string param
def E2E_TESTS = params.E2E_TESTS.split(' ')
def MIG_OPERATOR_RELEASE = params.MIG_OPERATOR_RELEASE
def DEBUG_SCRIPT = params.DEBUG_SCRIPT
def DEBUG_SCRIPT_ARGS = params.DEBUG_SCRIPT_ARGS
def COMMENT_TEXT = params.COMMENT_TEXT
def QUAYIO_CI_REPO = params.QUAYIO_CI_REPO
def QUAYIO_CI_REPO_OPERATOR = params.QUAYIO_CI_REPO_OPERATOR
def MIG_CI_OPERATOR_COURIER_BINARY = params.MIG_CI_OPERATOR_COURIER_BINARY

// credentials
def QUAYIO_CREDENTIALS = params.QUAYIO_CREDENTIALS
def EC2_ACCESS_KEY_ID = params.EC2_ACCESS_KEY_ID
def EC2_SECRET_ACCESS_KEY = params.EC2_SECRET_ACCESS_KEY
def OCP4_CREDENTIALS = params.OCP4_CREDENTIALS
def OCP3_CREDENTIALS = params.OCP3_CREDENTIALS
def EC2_SUB_USER = params.EC2_SUB_USER
def EC2_SUB_PASS = params.EC2_SUB_PASS

// boolean params
def E2E_DEPLOY_ONLY = params.E2E_DEPLOY_ONLY
def MIG_CONTROLLER_UI = params.MIG_CONTROLLER_UI
def USE_OLM = params.USE_OLM
def USE_DOWNSTREAM = params.USE_DOWNSTREAM
def DEBUG = params.DEBUG
def CLEAN_WORKSPACE = params.CLEAN_WORKSPACE
def USE_OLM = params.USE_OLM
def USE_DISCONNECTED = false

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

        utils.parse_comment_message(COMMENT_TEXT)

        // prepare for tests
        stage('Setup e2e environment') {
            steps_finished << 'Setup e2e environment'

            utils.prepare_workspace(SRC_CLUSTER_VERSION, DEST_CLUSTER_VERSION)
            utils.clone_mig_e2e()
        }

        // login to cluster
        withCredentials([
            [$class: 'UsernamePasswordMultiBinding', credentialsId: "${OCP3_CREDENTIALS}", usernameVariable: 'OCP3_ADMIN_USER', passwordVariable: 'OCP3_ADMIN_PASSWD'],
            [$class: 'UsernamePasswordMultiBinding', credentialsId: "${OCP4_CREDENTIALS}", usernameVariable: 'OCP4_ADMIN_USER', passwordVariable: 'OCP4_ADMIN_PASSWD']
            ]) {
                common_stages.login_cluster("${SRC_CLUSTER_URL}", "${OCP3_ADMIN_USER}", "${OCP3_ADMIN_PASSWD}", "${SRC_CLUSTER_VERSION}", SOURCE_KUBECONFIG).call()
                common_stages.login_cluster("${DEST_CLUSTER_URL}", "${OCP4_ADMIN_USER}", "${OCP4_ADMIN_PASSWD}", "${DEST_CLUSTER_VERSION}", TARGET_KUBECONFIG).call()
               }

        // clean up old stuff
        stage('Prepare for tests') {
            utils.teardown_mig_controller(SOURCE_KUBECONFIG)
            utils.teardown_mig_controller(TARGET_KUBECONFIG)
        }

        // build mig-controller image when not using default latest image
        if (env.MIG_CONTROLLER_REPO != 'https://github.com/konveyor/mig-controller.git' || 
            env.MIG_CONTROLLER_BRANCH != 'master') {
          utils.clone_mig_controller()
          common_stages.build_mig_controller().call()
        }

        // build mig-operator image when not using default latest image
        if (MIG_OPERATOR_BUILD_CUSTOM) { 
          utils.checkout_pr(MIG_OPERATOR_REPO, MIG_OPERATOR_PR_NO, 'mig-operator')
          common_stages.build_mig_operator().call()
        }

        // deploy mig-operator and mig-controller on source
        common_stages.deploy_mig_operator(SOURCE_KUBECONFIG, false, SRC_CLUSTER_VERSION).call()
        common_stages.deploy_mig_controller(SOURCE_KUBECONFIG, false, SRC_CLUSTER_VERSION).call()

        // deploy mig-operator and mig-controller on destination
        common_stages.deploy_mig_operator(TARGET_KUBECONFIG, true, DEST_CLUSTER_VERSION).call()
        common_stages.deploy_mig_controller(TARGET_KUBECONFIG, true, DEST_CLUSTER_VERSION).call()

        // Execute migration
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
