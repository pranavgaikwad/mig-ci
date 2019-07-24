// parallel-base.groovy

// Set Job properties and triggers
properties([
parameters([string(defaultValue: 'v3.11', description: 'OCP3 version to deploy', name: 'OCP3_VERSION', trim: false),
string(defaultValue: 'v4.1', description: 'OCP4 version to deploy', name: 'OCP4_VERSION', trim: false),
string(defaultValue: 'jenkins-ci-parallel-base', description: 'Cluster name to deploy', name: 'CLUSTER_NAME', trim: false),
string(defaultValue: 'jmatthew@redhat.com', description: 'Email to register the deploymnet', name: 'EMAIL', trim: false),
string(defaultValue: '1', description: 'Master count for cluster deployment', name: 'NODE_COUNT', trim: false),
string(defaultValue: '1', description: 'Node count for cluster deployment', name: 'MASTER_COUNT', trim: false),
string(defaultValue: '.mg.dog8code.com', description: 'Zone suffix for instance hostname address', name: 'BASESUFFIX', trim: false),
string(defaultValue: 'Z2GE8CSGW2ZA8W', description: 'Zone id', name: 'HOSTZONEID', trim: false),
string(defaultValue: 'ocp-workshop', description: 'AgnosticD environment type to deploy', name: 'ENVTYPE', trim: false),
string(defaultValue: 'ci', description: 'EC2 SSH key name to deploy on instances for remote access ', name: 'EC2_KEY', trim: false),
string(defaultValue: 'eu-west-1', description: 'AWS region to deploy instances', name: 'AWS_REGION', trim: false),
string(defaultValue: 'agnosticd', description: 'Deployment type to choose', name: 'DEPLOYMENT_TYPE', trim: false),
string(defaultValue: 'https://github.com/fusor/mig-controller.git', description: 'Mig controller repo to test', name: 'MIG_CONTROLLER_REPO', trim: false),
string(defaultValue: 'master', description: 'Mig controller repo branch to test', name: 'MIG_CONTROLLER_BRANCH', trim: false),
string(defaultValue: 'all', description: 'e2e test tags to run, see https://github.com/fusor/mig-e2e for details, space delimited', name: 'E2E_TESTS', trim: false),
string(defaultValue: 'quay.io/fbladilo/mig-controller', description: 'Repo for quay io mig-controller images', name: 'QUAYIO_CI_REPO', trim: false),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.UsernamePasswordMultiBinding', defaultValue: 'ci_quay_credentials', description: 'Credentials for quay.io container storage, used by mig-controller to push and pull images', name: 'QUAYIO_CREDENTIALS', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'agnosticd_own_repo', description: 'Private repo address for openshift-ansible packages', name: 'AGND_REPO', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_aws_access_key_id', description: 'EC2 access key ID for auth purposes', name: 'EC2_ACCESS_KEY_ID', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_aws_secret_access_key', description: 'EC2 private key needed to access instances, from Jenkins credentials store', name: 'EC2_SECRET_ACCESS_KEY', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: 'ci_ec2_key', description: 'EC2 private key needed to access instances, from Jenkins credentials store', name: 'EC2_PRIV_KEY', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: 'ci_pub_key', description: 'EC2 public key needed for agnosticd instances', name: 'EC2_PUB_KEY', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_rhel_sub_user', description: 'RHEL Openshift subscription account username', name: 'EC2_SUB_USER', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_rhel_sub_pass', description: 'RHEL Openshift subscription account password', name: 'EC2_SUB_PASS', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: 'ci_pull_secret', description: 'Pull secret needed for OCP4 deployments', name: 'OCP4_PULL_SECRET', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.UsernamePasswordMultiBinding', defaultValue: 'ci_ocp4_admin_credentials', description: 'Cluster admin credentials used in OCP4 deployments', name: 'OCP4_CREDENTIALS', required: true),
booleanParam(defaultValue: true, description: 'Clean up workspace after build', name: 'CLEAN_WORKSPACE'),
booleanParam(defaultValue: true, description: 'EC2 terminate instances after build', name: 'EC2_TERMINATE_INSTANCES')])])

// true/false build parameter that defines if we terminate instances once build is done
def EC2_TERMINATE_INSTANCES = params.EC2_TERMINATE_INSTANCES
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

        stage('Setup for source and target cluster') {
            steps_finished << 'Setup for source and target cluster'

            utils.prepare_workspace("${env.OCP3_VERSION}", "${env.OCP4_VERSION}")
            utils.copy_private_keys()
            utils.copy_public_keys()
            utils.clone_related_repos()
            utils.clone_mig_controller()
            if (env.DEPLOYMENT_TYPE == 'agnosticd') {
                utils.prepare_agnosticd()
            } else if (env.DEPLOYMENT_TYPE != 'OA') {
                utils.prepare_origin3_dev()
            }
        }

        stage('Deploy clusters') {
            steps_finished << 'Deploy clusters'
            parallel deploy_OCP3: {
                if (env.DEPLOYMENT_TYPE == 'agnosticd') {
                    common_stages.deploy_ocp3_agnosticd(SOURCE_KUBECONFIG).call()
                } else if (env.DEPLOYMENT_TYPE == 'OA') {
                    common_stages.deployOCP3_OA(SOURCE_KUBECONFIG, CLUSTER_NAME + '-v3-' + BUILD_NUMBER).call()
                } else {
                    common_stages.deploy_origin3_dev(SOURCE_KUBECONFIG).call()
                }
            }, deploy_NFS: {
                common_stages.deploy_NFS(CLUSTER_NAME + '-' + BUILD_NUMBER).call()
            }, deploy_OCP4: {
                common_stages.deployOCP4(TARGET_KUBECONFIG).call()
            },
            failFast: true
        }

        common_stages.provision_pvs(SOURCE_KUBECONFIG, CLUSTER_NAME + '-' + BUILD_NUMBER).call()

        common_stages.deploy_mig_controller_on_both(SOURCE_KUBECONFIG, TARGET_KUBECONFIG, false, true).call()

        common_stages.execute_migration(E2E_TESTS, SOURCE_KUBECONFIG, TARGET_KUBECONFIG).call()

    } catch (Exception ex) {
        currentBuild.result = "FAILED"
        println(ex.toString())
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
                            withEnv(['PATH+EXTRA=~/bin']) {
                                parallel destroy_OCP3: {
                                    if (env.DEPLOYMENT_TYPE == 'agnosticd') {
                                        utils.teardown_ocp3_agnosticd()
                                    } else if (env.DEPLOYMENT_TYPE == 'OA') {
                                        utils.teardown_OCP3_OA(CLUSTER_NAME + '-v3-' + BUILD_NUMBER)
                                    } else {
                                        utils.teardown_origin3_dev()
                                    }
                                }, destroy_OCP4: {
                                    utils.teardown_OCP4()
                                }, destroy_NFS: {
                                    utils.teardown_nfs(CLUSTER_NAME + '-' + BUILD_NUMBER)
                                }, failFast: false
                            }
                        }
                }
            if (CLEAN_WORKSPACE) {
                utils.teardown_container_image()
                cleanWs cleanWhenFailure: false, notFailBuild: true
            }
        }
    }
    }
}
