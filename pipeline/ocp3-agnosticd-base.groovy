// ocp311-OA-base.groovy

// Set Job properties and triggers
properties([
parameters([
string(defaultValue: 'v3.11', description: 'OpenShift version to deploy', name: 'OCP3_VERSION', trim: false),
string(defaultValue: 'jenkins-ci-agnosticd-base', description: 'Cluster name to deploy', name: 'CLUSTER_NAME', trim: false),
string(defaultValue: 'jmatthew@redhat.com', description: 'Email to register the deploymnet', name: 'EMAIL', trim: false),
string(defaultValue: '1', description: 'Node count for cluster deployment', name: 'NODE_COUNT', trim: false),
string(defaultValue: '1', description: 'Node count for cluster deployment', name: 'MASTER_COUNT', trim: false),
string(defaultValue: '.mg.dog8code.com', description: 'Zone suffix for instance hostname address', name: 'BASESUFFIX', trim: false),
string(defaultValue: 'Z2GE8CSGW2ZA8W', description: 'Zone id', name: 'HOSTZONEID', trim: false),
string(defaultValue: 'ocp-workshop', description: 'AgnosticD environment type to deploy', name: 'ENVTYPE', trim: false),
string(defaultValue: 'ci', description: 'EC2 SSH key name to deploy on instances for remote access ', name: 'EC2_KEY', trim: false),
string(defaultValue: 'eu-west-1', description: 'AWS region to deploy instances', name: 'AWS_REGION', trim: false),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'agnosticd_own_repo', description: 'Private repo address for openshift-ansible packages', name: 'AGND_REPO', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_aws_access_key_id', description: 'EC2 access key ID for auth purposes', name: 'EC2_ACCESS_KEY_ID', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_aws_secret_access_key', description: 'EC2 private key needed to access instances, from Jenkins credentials store', name: 'EC2_SECRET_ACCESS_KEY', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: 'ci_ec2_key', description: 'EC2 private key needed to access instances, from Jenkins credentials store', name: 'EC2_PRIV_KEY', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: 'ci_pub_key', description: 'EC2 public key needed for agnosticd instances', name: 'EC2_PUB_KEY', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_rhel_sub_user', description: 'RHEL Openshift subscription account username', name: 'EC2_SUB_USER', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_rhel_sub_pass', description: 'RHEL Openshift subscription account password', name: 'EC2_SUB_PASS', required: true),
booleanParam(defaultValue: true, description: 'EC2 terminate instances after build', name: 'EC2_TERMINATE_INSTANCES')])])

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
            steps_finished << 'Setup Build Environment agnosticd OCP3 ' + "${OCP3_VERSION}"

            utils.prepare_workspace("${env.OCP3_VERSION}", '')

            utils.copy_private_keys()

            utils.clone_related_repos()

            utils.prepare_agnosticd()
        }

        common_stages.deploy_ocp3_agnosticd().call()

        common_stages.load_sample_data(KUBECONFIG_OCP3).call()

        common_stages.sanity_checks(KUBECONFIG_OCP3).call()

    } catch (Exception ex) {
        currentBuild.result = "FAILED"
        println(ex.toString())
        throw ex
    } finally {
        // Success or failure, always send notifications
        utils.notifyBuild(currentBuild.result)
        stage('Clean Up Environment') {
            utils.teardown_ocp3_agnosticd()
            cleanWs cleanWhenFailure: false, notFailBuild: true
        }
    }
}
