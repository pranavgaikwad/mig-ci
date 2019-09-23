// ocp3-base
properties([
parameters([
choice(choices: ['3.7', '3.9', '3.10', '3.11'], description: 'OCP3 version to deploy', name: 'SRC_CLUSTER_VERSION'),
string(defaultValue: 'jenkins-ci-ocp3-base', description: 'Cluster name to deploy', name: 'CLUSTER_NAME', trim: false),
string(defaultValue: 'mig-ci@redhat.com', description: 'Email to register the deployment', name: 'EMAIL', trim: false),
string(defaultValue: '1', description: 'OCP3 master instance count', name: 'OCP3_MASTER_INSTANCE_COUNT', trim: false),
string(defaultValue: '1', description: 'OCP3 worker instance count', name: 'OCP3_WORKER_INSTANCE_COUNT', trim: false),
string(defaultValue: '1', description: 'OCP3 infra instance count', name: 'OCP3_INFRA_INSTANCE_COUNT', trim: false),
string(defaultValue: 'm4.large', description: 'OCP3 master instance type', name: 'OCP3_MASTER_INSTANCE_TYPE', trim: false),
string(defaultValue: 'm4.xlarge', description: 'OCP3 worker instance type', name: 'OCP3_WORKER_INSTANCE_TYPE', trim: false),
string(defaultValue: 'm4.large', description: 'OCP3 infra instance type', name: 'OCP3_INFRA_INSTANCE_TYPE', trim: false),
string(defaultValue: '.mg.dog8code.com', description: 'Zone suffix for instance hostname address', name: 'BASESUFFIX', trim: false),
string(defaultValue: 'Z2GE8CSGW2ZA8W', description: 'Zone id', name: 'HOSTZONEID', trim: false),
string(defaultValue: 'ocp-workshop', description: 'AgnosticD environment type to deploy', name: 'ENVTYPE', trim: false),
string(defaultValue: 'ci', description: 'EC2 SSH key name to deploy on instances for remote access ', name: 'EC2_KEY', trim: false),
string(defaultValue: 'us-west-1', description: 'AWS region to deploy instances', name: 'AWS_REGION', trim: false),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'agnosticd_own_repo', description: 'Private repo address for openshift-ansible packages', name: 'AGND_REPO', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_aws_access_key_id', description: 'EC2 access key ID for auth purposes', name: 'EC2_ACCESS_KEY_ID', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_aws_secret_access_key', description: 'EC2 private key needed to access instances, from Jenkins credentials store', name: 'EC2_SECRET_ACCESS_KEY', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: 'ci_ec2_key', description: 'EC2 private key needed to access instances, from Jenkins credentials store', name: 'EC2_PRIV_KEY', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: 'ci_pub_key', description: 'EC2 public key needed for agnosticd instances', name: 'EC2_PUB_KEY', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_rhel_sub_user', description: 'RHEL Openshift subscription account username', name: 'EC2_SUB_USER', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_rhel_sub_pass', description: 'RHEL Openshift subscription account password', name: 'EC2_SUB_PASS', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: 'ci_pull_secret', description: 'Pull secret needed for OCP4 deployments', name: 'OCP4_PULL_SECRET', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.UsernamePasswordMultiBinding', defaultValue: 'ci_ocp3_admin_credentials', description: 'Cluster admin credentials used in OCP3 deployments', name: 'OCP3_CREDENTIALS', required: true),
booleanParam(defaultValue: false, description: 'Update OCP3 cluster packages to latest', name: 'OCP3_UPDATE'),
booleanParam(defaultValue: false, description: 'Provision OCP3 glusterfs', name: 'OCP3_GLUSTERFS'),
booleanParam(defaultValue: false, description: 'Persistent cluster builds with fixed hostname', name: 'PERSISTENT'),
booleanParam(defaultValue: true, description: 'Clean up workspace after build', name: 'CLEAN_WORKSPACE'),
booleanParam(defaultValue: true, description: 'EC2 terminate instances after build', name: 'EC2_TERMINATE_INSTANCES')])])

// true/false build parameter that defines if we terminate instances once build is done
EC2_TERMINATE_INSTANCES = params.EC2_TERMINATE_INSTANCES
// true/false build parameter that defines if we cleanup workspace once build is done
CLEAN_WORKSPACE = params.CLEAN_WORKSPACE
// true/false persistent clusters
PERSISTENT = params.PERSISTENT
// true/false provision glusterfs
OCP3_GLUSTERFS = params.OCP3_GLUSTERFS

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
        utils = load "${env.WORKSPACE}/pipeline/utils.groovy"
        common_stages = load "${env.WORKSPACE}/pipeline/common_stages.groovy"

        utils.notifyBuild('STARTED')

        stage('Setup Build Environment OCP3') {
            steps_finished << 'Setup Build Environment OCP3 ' + SRC_CLUSTER_VERSION
            utils.prepare_workspace(SRC_CLUSTER_VERSION, '')
            utils.copy_private_keys()
            utils.prepare_agnosticd()
        }

        common_stages.deploy_ocp3_agnosticd(SOURCE_KUBECONFIG, SRC_CLUSTER_VERSION).call()
        common_stages.sanity_checks(SOURCE_KUBECONFIG).call()

    } catch (Exception ex) {
        currentBuild.result = "FAILED"
        println(ex.toString())
    } finally {
        utils.notifyBuild(currentBuild.result)
        stage('Clean Up Environment') {
          if (EC2_TERMINATE_INSTANCES) {
            utils.teardown_ocp_agnosticd(SRC_CLUSTER_VERSION)
          }
          if (CLEAN_WORKSPACE) {
            cleanWs cleanWhenFailure: false, notFailBuild: true
          }
        }
      }
  }
}
