// ocp4-base.groovy

// Set Job properties and triggers
properties([
parameters([
string(defaultValue: 'v4.1', description: 'OCP4 version to deploy', name: 'OCP4_VERSION', trim: false),
string(defaultValue: 'jenkins-ci', description: 'OCP4 cluster name to deploy', name: 'OCP4_CLUSTER_NAME', trim: false), 
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_aws_access_key_id', description: 'EC2 access key ID for auth purposes', name: 'EC2_ACCESS_KEY_ID', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_aws_secret_access_key', description: 'EC2 private key needed to access instances, from Jenkins credentials store', name: 'EC2_SECRET_ACCESS_KEY', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: 'ci_pull_secret', description: 'Pull secret needed for OCP4 deployments', name: 'OCP4_PULL_SECRET', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: 'ci_pub_key', description: 'EC2 public key needed for OCP4 instances', name: 'EC2_PUB_KEY', required: true),
string(defaultValue: 'ci', description: 'EC2 SSH key name to deploy on instances for remote access ', name: 'EC2_KEY', trim: false),
string(defaultValue: 'eu-west-1', description: 'EC2 region to deploy instances', name: 'EC2_REGION', trim: false),
string(defaultValue: 'https://github.com/Danil-Grigorev/mig-ci.git', description: 'MIG CI repo URL to checkout', name: 'MIG_CI_REPO', trim: false), // TODO: change to upstream
string(defaultValue: 'oa-to-pipelines', description: 'MIG CI repo branch to checkout', name: 'MIG_CI_BRANCH', trim: false),
booleanParam(defaultValue: true, description: 'Clean up workspace after build', name: 'CLEAN_WORKSPACE'),
booleanParam(defaultValue: true, description: 'EC2 terminate instances after build', name: 'EC2_TERMINATE_INSTANCES')]),
pipelineTriggers([cron('@midnight')])])

// true/false build parameter that defines if we terminate instances once build is done
def EC2_TERMINATE_INSTANCES = params.EC2_TERMINATE_INSTANCES
// true/false build parameter that defines if we cleanup workspace once build is done
def CLEAN_WORKSPACE = params.CLEAN_WORKSPACE

def common_stages

steps_finished = []

echo "Running job ${env.JOB_NAME}, build ${env.BUILD_ID} on ${env.JENKINS_URL}"
echo "Build URL ${env.BUILD_URL}"
echo "Job URL ${env.JOB_URL}"

node {
    try {
        stage('Prepare Build Environment') {
            checkout scm
            common_stages = load "${env.WORKSPACE}/pipeline/common_stages.groovy"

            echo "$common_stages"
            common_stages.notifyBuild('STARTED')   
            steps_finished << 'Prepare Build Environment'
            echo 'Cloning mig-ci repo'
            checkout([$class: 'GitSCM', branches: [[name: "*/$MIG_CI_BRANCH"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'mig-ci']], submoduleCfg: [], userRemoteConfigs: [[url: "$MIG_CI_REPO"]]])
            
            // Create default keys dir
            KEYS_DIR = "${env.WORKSPACE}" + '/keys'
            sh "mkdir -p ${KEYS_DIR}"
            sh "mkdir -p ${env.WORKSPACE}/kubeconfigs"


            // Prepare pull secret
            withCredentials([file(credentialsId: "$OCP4_PULL_SECRET", variable: "PULL_SECRET")]) {
                sh "cat ${PULL_SECRET} > ${KEYS_DIR}/pull-secret"
            }
            
            // Prepare EC2 pub key for ansible consumption
            withCredentials([file(credentialsId: "$EC2_PUB_KEY", variable: "SSH_PUB_KEY")]) {
                sh "cat ${SSH_PUB_KEY} > ${KEYS_DIR}/${EC2_KEY}.pub"
            }
        }
        
        common_stages.deployOCP4().call()

        stage('Deploy Velero and configure S3 storage') {
            steps_finished << 'Deploy Velero and configure S3 storage'
            withCredentials([
                string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
                string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY')
                ]) 
            {
                withEnv(['PATH+EXTRA=~/bin']) {
                    dir('mig-ci') {
                        ansiColor('xterm') {
                            ansiblePlaybook(
                                playbook: 'setup_velero.yml',
                                hostKeyChecking: false,
                                unbuffered: true,
                                colorized: true)
                        }
                    }
                }
            }
        }
        
    } catch (e) {
        currentBuild.result = "FAILED"
        throw e
    } finally {
        // Success or failure, always send notifications
        common_stages.notifyBuild(currentBuild.result)
        stage('Clean Up Environment') {
        // Success or failure, always terminate instances if requested
            common_stages.teardown_OCP4()
            if (CLEAN_WORKSPACE) {
                cleanWs cleanWhenFailure: false, notFailBuild: true
            }
        }
	}
}
