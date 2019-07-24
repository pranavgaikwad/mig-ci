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

def clone_mig_e2e() {
  echo 'Cloning mig-e2e repo'
  checkout([$class: 'GitSCM', branches: [[name: "${MIG_E2E_BRANCH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'mig-e2e']], submoduleCfg: [], userRemoteConfigs: [[url: "${MIG_E2E_REPO}"]]])
}

def prepare_cpma(repo = '', branch = '') {
  if (repo == '') {
    repo = "https://github.com/fusor/cpma.git"
  }
  if (branch == '') {
    branch = "master"
  }
  checkout([$class: 'GitSCM', branches: [[name: branch]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'cpma']], submoduleCfg: [], userRemoteConfigs: [[url: repo]]])
}


def prepare_origin3_dev() {
  echo 'Cloning origin3-dev repo'
  checkout([$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'origin3-dev']], submoduleCfg: [], userRemoteConfigs: [[url: 'https://github.com/fusor/origin3-dev.git']]])

  dir('origin3-dev') {
      sh 'cp -f config.yml.example config.yml'
      sh 'rm -f overrides.yml'
      def overrides = ['ec2_key': "${EC2_KEY}",
          'ec2_private_key_file': "${KEYS_DIR}/${EC2_KEY}.pem",
          'ec2_instance_type': "m4.large",
          'ec2_repo_create': false,
          'openshift_setup_client_version': "$OCP3_VERSION",
          'openshift_setup_remote_auto_login': true,
          'openshift_setup_cluster_retries': 5]
      writeYaml file: 'overrides.yml', data: overrides
  }
}


def clone_mig_controller() {
  echo 'Cloning mig-controller repo'
  checkout([$class: 'GitSCM', branches: [[name: "${MIG_CONTROLLER_BRANCH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'mig-controller']], submoduleCfg: [], userRemoteConfigs: [[url: "${MIG_CONTROLLER_REPO}"]]])
}


def prepare_agnosticd() {
  sh 'test -e ~/.local/bin/aws || pip install awscli --upgrade --user'

  sh 'rm -f agnosticd/ansible.cfg'

  checkout([$class: 'GitSCM', branches: [[name: '8780932ab2917e000b7b05297a7a63d0b7397a28']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'agnosticd']], submoduleCfg: [], userRemoteConfigs: [[url: 'https://github.com/redhat-cop/agnosticd.git']]])

  // Fixes
  withCredentials([file(credentialsId: "${env.EC2_PUB_KEY}", variable: "SSH_PUB_KEY")]) {
    sh "cat ${SSH_PUB_KEY} > ${CLUSTER_NAME}-v3-${BUILD_NUMBER}key.pub"
  }

  withCredentials([file(credentialsId: "${env.EC2_PRIV_KEY}", variable: "SSH_PRIV_KEY")]) {
    sh "cat ${SSH_PRIV_KEY} > ${CLUSTER_NAME}-v3-${BUILD_NUMBER}key"
    sh "chmod 600 ${CLUSTER_NAME}-v3-${BUILD_NUMBER}key"
  }

  def readContent = readFile 'agnosticd/ansible.cfg'
  writeFile file: 'agnosticd/ansible.cfg', text: readContent+"\r\n\n[ssh_connection]\r\nssh_args = -C -o ControlMaster=auto -o ControlPersist=216000s -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"
}

def prepare_workspace(ocp3_version = '', ocp4_version = '') {
  // Prepare EC2 key for ansible consumption
  KEYS_DIR = "${env.WORKSPACE}" + '/keys'
  sh "mkdir -p ${KEYS_DIR}"
  sh "mkdir -p ${env.WORKSPACE}/kubeconfigs"

  // Target kubeconfig locations
  if ("${ocp3_version}" != '') {
    SOURCE_KUBECONFIG = "${env.WORKSPACE}/kubeconfigs/ocp-${ocp3_version}-kubeconfig"
  }
  if ("${ocp4_version}" != '') {
    TARGET_KUBECONFIG = "${env.WORKSPACE}/kubeconfigs/ocp-${ocp4_version}-kubeconfig"
  }

  OC_BINARY = "${env.WORKSPACE}/bin/oc"
  sh 'touch destroy_env.sh && chmod +x destroy_env.sh'
}


def copy_private_keys() {
  PRIVATE_KEY = "${KEYS_DIR}/${env.EC2_KEY}.pem"
  withCredentials([file(credentialsId: "${env.EC2_PRIV_KEY}", variable: "SSH_PRIV_KEY")]) {
    sh "cat ${SSH_PRIV_KEY} > ${PRIVATE_KEY}"
    sh "chmod 600 ${PRIVATE_KEY}"
  }
}


def copy_public_keys() {
  // Prepare pull secret
  withCredentials([file(credentialsId: "${env.OCP4_PULL_SECRET}", variable: "PULL_SECRET")]) {
    sh "cat ${PULL_SECRET} > ${KEYS_DIR}/pull-secret"
  }

  // Prepare EC2 pub key for ansible consumption
  PUBLIC_KEY = "${KEYS_DIR}/${env.EC2_KEY}.pub"
  withCredentials([file(credentialsId: "${env.EC2_PUB_KEY}", variable: "SSH_PUB_KEY")]) {
    sh "cat ${SSH_PUB_KEY} > ${KEYS_DIR}/${env.EC2_KEY}.pub"
  }
}


def teardown_origin3_dev() {
  if (EC2_TERMINATE_INSTANCES) {
    withCredentials([
      string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
      string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY')
      ])
    {
      dir('origin3-dev') {
        ansiColor('xterm') {
          ansiblePlaybook(
            playbook: 'terminate.yml',
            extras: '-e "ec2_force_terminate_instances=true"',
            hostKeyChecking: false,
            unbuffered: true,
            colorized: true)
        }
      }
    }
  }
}


def teardown_OCP3_OA(prefix = '') {
  if (prefix != '') {
    prefix = "-e prefix=${prefix}"
  }
  if (EC2_TERMINATE_INSTANCES) {
    withCredentials([
        string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
        string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY'),
        ]) {
      echo "Region: ${env.AWS_REGION}"
      ansiColor('xterm') {
        ansiblePlaybook(
          playbook: 'destroy_ocp3_cluster.yml',
          extras: "${prefix}",
          hostKeyChecking: false,
          unbuffered: true,
          colorized: true)
      }
    }
  }
}

def teardown_ocp3_agnosticd() {
  if (EC2_TERMINATE_INSTANCES) {
    dir("agnosticd") {
      withCredentials([
          string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
          string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY'),
          ])
      {
        def teardown_vars = [
          'aws_region': "${AWS_REGION}",
          'guid': "${CLUSTER_NAME}-v3-${BUILD_NUMBER}",
          'env_type': "ocp-workshop",
          'cloud_provider': "ec2",
          'aws_access_key_id': "${AWS_ACCESS_KEY_ID}",
          'aws_secret_access_key': "${AWS_SECRET_ACCESS_KEY}"
        ]
        sh 'rm -f teardown_vars.yml'
        writeYaml file: 'teardown_vars.yml', data: teardown_vars
        teardown_vars = teardown_vars.collect { e -> '-e ' + e.key + '=' + e.value }

        withEnv(['PATH+EXTRA=~/.local/bin']) {
          ansiblePlaybook(
            playbook: "ansible/configs/${ENVTYPE}/destroy_env.yml",
            extras: "${teardown_vars.join(' ')}",
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
      ]) {
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

def teardown_nfs(prefix = '') {
  if (prefix != '') {
    prefix = "-e prefix=${prefix}"
  }
  if (EC2_TERMINATE_INSTANCES) {
    ansiColor('xterm') {
      ansiblePlaybook(
        playbook: 'nfs_server_destroy.yml',
        hostKeyChecking: false,
        extras: "${prefix}",
        unbuffered: true,
        colorized: true)
    }
  }
}

def teardown_mig_controller(kubeconfig) {
  withEnv([ "KUBECONFIG=${kubeconfig}" ]) {
    ansiColor('xterm') {
      ansiblePlaybook(
        playbook: 'mig_controller_destroy.yml',
        hostKeyChecking: false,
        unbuffered: true,
        colorized: true)
    }
  }
}

def teardown_container_image() {
  // Check if is not upstream
  if (env.QUAYIO_CI_REPO && "${MIG_CONTROLLER_REPO}" != "https://github.com/fusor/mig-controller.git") {
    ansiColor('xterm') {
      ansiblePlaybook(
        playbook: 'container_image_destroy.yml',
        hostKeyChecking: false,
        extras: "-e quayio_ci_repo=${QUAYIO_CI_REPO} -e quayio_ci_tag=${MIG_CONTROLLER_BRANCH}",
        unbuffered: true,
        colorized: true)
    }
  }
}


return this
