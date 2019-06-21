// utils.groovy

def notifyBuild(String buildStatus = 'STARTED') {
  // build status of null means successful
  buildStatus =  buildStatus ?: 'SUCCESSFUL'
 
  // Default values
  def colorName = 'RED'
  def colorCode = '#FF0000'
  def subject = "${buildStatus}: Job '${env.JOB_NAME}, build [${env.BUILD_NUMBER}]'"
  def summary = "${subject}\nLink: (${env.BUILD_URL})\n"
  def results = []

  for (i = 0; i < steps_finished.size() - 1; i++) {
    results.add(':heavy_check_mark:')
  }

  // Override default values based on build status
  if (buildStatus == 'STARTED') {
    colorCode = '#FFFF00'
  } else if (buildStatus == 'SUCCESSFUL') {
    colorCode = '#00FF00'
    results.add(':heavy_check_mark:')
    steps_finished.eachWithIndex { step, id ->
      summary = summary + results[id] + '\t' + step + '\n'
    }
  } else {
    colorCode = '#FF0000'
    results.add(':x:')
    steps_finished.eachWithIndex { step, id ->
      summary = summary + results[id] + '\t' + step + '\n'
    }
  }
 
  // Send notifications
  slackSend (color: colorCode, message: summary)
}


def clone_related_repos() {
  echo 'Cloning mig-e2e repo'
  checkout([$class: 'GitSCM', branches: [[name: "*/master"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'mig-e2e']], submoduleCfg: [], userRemoteConfigs: [[url: "https://github.com/fusor/mig-e2e.git"]]])

  echo 'Cloning ocp-mig-test-data repo'
  checkout([$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'ocp-mig-test-data']], submoduleCfg: [], userRemoteConfigs: [[url: 'https://github.com/fusor/ocp-mig-test-data.git']]])
}


def prepare_workspace(ocp3_version = '', ocp4_version = '') {
  // Prepare EC2 key for ansible consumption
  KEYS_DIR = "${env.WORKSPACE}" + '/keys'
  sh "mkdir -p ${KEYS_DIR}"
  sh "mkdir -p ${env.WORKSPACE}/kubeconfigs"

  // Set enviroment variables
  KUBECONFIG_TMP = "${env.WORKSPACE}/kubeconfigs/kubeconfig"

  // Target kubeconfig locations
  if ("${ocp3_version}" != '') {
    KUBECONFIG_OCP3 = "${env.WORKSPACE}/kubeconfigs/ocp-${ocp3_version}-kubeconfig"
  }
  if ("${ocp4_version}" != '') {
    KUBECONFIG_OCP4 = "${env.WORKSPACE}/kubeconfigs/ocp-${ocp4_version}-kubeconfig"
  }

  sh "rm -f ${KUBECONFIG_TMP}"

}


def copy_private_keys() {
  withCredentials([file(credentialsId: "${env.EC2_PRIV_KEY}", variable: "SSH_PRIV_KEY")]) {
    sh "cat ${SSH_PRIV_KEY} > ${KEYS_DIR}/${env.EC2_KEY}.pem"
    sh "chmod 600 ${KEYS_DIR}/${env.EC2_KEY}.pem"
  }
}


def copy_public_keys() {
  // Prepare pull secret
  withCredentials([file(credentialsId: "${env.OCP4_PULL_SECRET}", variable: "PULL_SECRET")]) {
    sh "cat ${PULL_SECRET} > ${KEYS_DIR}/pull-secret"
  }

  // Prepare EC2 pub key for ansible consumption
  withCredentials([file(credentialsId: "${env.EC2_PUB_KEY}", variable: "SSH_PUB_KEY")]) {
    sh "cat ${SSH_PUB_KEY} > ${KEYS_DIR}/${env.EC2_KEY}.pub"
  }
}


return this
