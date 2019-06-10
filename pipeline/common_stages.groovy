// common_stages.groovy

def deployOCP3_OA(extras = '') {
  if ("${extras}" != '') {
    extras = "-e ${extras}"
  }

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
        withEnv(['PATH+EXTRA=~/bin', "KUBECONFIG=${KUBECONFIG_TMP}", "AWS_REGION=${env.EC2_REGION}"]) {
          echo "$AWS_REGION"
          ansiColor('xterm') {
            ansiblePlaybook(
              playbook: 'deploy_ocp3_cluster.yml',
              extras: "${extras}",
              hostKeyChecking: false,
              unbuffered: true,
              colorized: true)
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
        withEnv([
            'PATH+EXTRA=~/bin',
            "KUBECONFIG=${KUBECONFIG_TMP}",
            "EC2_REGION=${env.EC2_REGION}",
            "AWS_REGION=${env.EC2_REGION}"])
          {
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


def deploy_NFS() {
  return {
    stage('Configure NFS storage') {
      steps_finished << 'Configure NFS storage'
      ansiColor('xterm') {
        ansiblePlaybook(
          playbook: 'nfs_server_deploy.yml',
          hostKeyChecking: false,
          unbuffered: true,
          colorized: true)
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
    stage('Run OCP3 Sanity Checks') {
      steps_finished << 'Run OCP3 Sanity Checks'
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


def teardown_OCP3_OA(extras = '') {
  if ("${extras}" != '') {
    extras = "-e ${extras}"
  }
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
            extras: "${extras}",
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
