// common_stages.groovy


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

def setup_OCP3_OA() {
  return {
    stage('Prepare Build Environment') {
      steps_finished << 'Prepare Build Environment'
      // Prepare EC2 key for ansible consumption
      KEYS_DIR = "${env.WORKSPACE}" + '/keys'
      sh "mkdir -p ${KEYS_DIR}"
      sh "mkdir -p ${env.WORKSPACE}/kubeconfigs"

      KUBECONFIG_TMP = "${env.WORKSPACE}/kubeconfigs/kubeconfig"

      withCredentials([file(credentialsId: "$EC2_PRIV_KEY", variable: "SSH_PRIV_KEY")]) {
          sh "cat ${SSH_PRIV_KEY} > ${KEYS_DIR}/${EC2_KEY}.pem"
          sh "chmod 600 ${KEYS_DIR}/${EC2_KEY}.pem"
      }

      echo 'Cloning ocp-mig-test-data repo'
      checkout([$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'ocp-mig-test-data']], submoduleCfg: [], userRemoteConfigs: [[url: 'https://github.com/fusor/ocp-mig-test-data.git']]])
      
      echo 'Cloning mig-ci repo'
      checkout([$class: 'GitSCM', branches: [[name: "*/$MIG_CI_BRANCH"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'mig-ci']], submoduleCfg: [], userRemoteConfigs: [[url: "$MIG_CI_REPO"]]])
    }
  }
}

def deployOCP3_OA() {
  return {
    stage('Deploy OCP3 OA cluster') {
      steps_finished << 'Deploy OCP3 OA cluster'
      withCredentials([
          string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
          string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY'),
          string(credentialsId: "$EC2_SUB_USER", variable: 'SUB_USER'),
          string(credentialsId: "$EC2_SUB_PASS", variable: 'SUB_PASS')
          ])
      {
        dir('mig-ci') {
          withEnv(['PATH+EXTRA=~/bin', "KUBECONFIG=${KUBECONFIG_TMP}", "AWS_REGION=${env.EC2_REGION}"]) {
            echo "$AWS_REGION"
            ansiColor('xterm') {
              ansiblePlaybook(
                playbook: 'deploy_ocp3_cluster.yml',
                hostKeyChecking: false,
                unbuffered: true,
                colorized: true)
            }
          }
        }
      }
    }
  }
}


def deployOCP4() {
  return {
    stage('Deploy OCP4 cluster') {
      steps_finished << 'Deploy OCP4'
      withCredentials([
          string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
          string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY')
          ]) 
      {
        dir('mig-ci') {
          withEnv(['PATH+EXTRA=~/bin', "KUBECONFIG=${KUBECONFIG_TMP}", "AWS_REGION=${env.EC2_REGION}"]) {
            ansiColor('xterm') {
              ansiblePlaybook(
                playbook: 'deploy_ocp4_cluster.yml',
                hostKeyChecking: false,
                unbuffered: true,
                colorized: true)
            }
          }
        }
      }
    }
  }
}


def load_sample_data() {
  return {
    stage('Load Sample Data/Apps on OCP3') {
      steps_finished << 'Load Sample Data/Apps on OCP3'
      dir('ocp-mig-test-data') {
        withEnv(['PATH+EXTRA=~/bin', "KUBECONFIG=${KUBECONFIG_TMP}", "AWS_REGION=${env.EC2_REGION}"]) {
          echo "$AWS_REGION"
          ansiColor('xterm') {
            ansiblePlaybook(
              playbook: 'nginx.yml',
              extras: '-e "with_backup=false" -e "with_restore=false"',
              hostKeyChecking: false,
              unbuffered: true,
              colorized: true)
          }
        }
      }
    }
  }
}


def sanity_checks() {
  return {
    stage('Run OCP3 Router Sanity Checks') {
      steps_finished << 'Run OCP3 Router Sanity Checks'
      dir('mig-ci') {
        withEnv(['PATH+EXTRA=~/bin', "KUBECONFIG=${KUBECONFIG_TMP}", "AWS_REGION=${env.EC2_REGION}"]) {
          echo "$AWS_REGION"
          ansiColor('xterm') {
            ansiblePlaybook(
              playbook: 'ocp_sanity_check.yml',
              hostKeyChecking: false,
              unbuffered: true,
              colorized: true)
          }
        }
      }
    }
  }
}


def teardown_OCP3_OA() {
  if (EC2_TERMINATE_INSTANCES) {
    withCredentials([
        string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
        string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY'),
        ]) 
    {
      withEnv(['PATH+EXTRA=~/bin', "AWS_REGION=${env.EC2_REGION}"]) {
        echo "$AWS_REGION"
        ansiColor('xterm') {
          ansiblePlaybook(
            playbook: 'destroy_ocp3_cluster.yml',
            hostKeyChecking: false,
            unbuffered: true,
            colorized: true)
        }
      }
    }
  }
}


def teardown_OCP4() {
  if (EC2_TERMINATE_INSTANCES) {
    withCredentials([
      string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
      string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY')
      ]) 
    {
      withEnv(['PATH+EXTRA=~/bin']) {
        ansiColor('xterm') {
          ansiblePlaybook(
            playbook: 'destroy_ocp4_cluster.yml',
            hostKeyChecking: false,
            unbuffered: true,
            colorized: true)
        }
      }
    }
  }
}

return this
