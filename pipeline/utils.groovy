// utils.groovy
def notifyBuild(String buildStatus = 'STARTED') {
  // build status of null means successful
  buildStatus =  buildStatus ?: 'SUCCESSFUL'

  // Default values
  def colorName = 'RED'
  def colorCode = '#FF0000'
  def subject = "${buildStatus}: Job '${env.JOB_NAME}, build [${env.BUILD_NUMBER}]'"
  def summary = "${subject}\nLink: (${env.BUILD_URL})\n"
  def body = ""
  def message = "${summary}${body}"
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
      body = body + results[id] + '\t' + step + '\n'
    }
  } else {
    colorCode = '#FF0000'
    results.add(':x:')
    steps_finished.eachWithIndex { step, id ->
      body = body + results[id] + '\t' + step + '\n'
    }
  }

  update_build_status(body)

  // Send notifications
  // slackSend (color: colorCode, message: message)
}

def update_build_status(body) {
  def mention = PR_AUTHOR ? "${PR_AUTHOR}\n" : ""
  comment = mention + body + "\nFind full log [here](${env.BUILD_URL})"
  sh "echo '${comment}' >> ${JENKINS_HOME}/jobs/${JOB_NAME}/builds/${BUILD_NUMBER}/summary"
}

def clone_mig_e2e() {
  echo 'Cloning mig-e2e repo'
  checkout([$class: 'GitSCM', branches: [[name: "${MIG_E2E_BRANCH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'mig-e2e']], submoduleCfg: [], userRemoteConfigs: [[url: "${MIG_E2E_REPO}"]]])
}

def clone_mig_controller() {
  echo 'Cloning mig-controller repo'
  checkout([$class: 'GitSCM', branches: [[name: "${MIG_CONTROLLER_BRANCH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'mig-controller']], submoduleCfg: [], userRemoteConfigs: [[url: "${MIG_CONTROLLER_REPO}"]]])
}

def prepare_agnosticd() {
  sh 'test -e ~/.local/bin/aws || pip install awscli --upgrade --user'

// Fix checkout to commit before boto removal on agnosticd development branch , see https://github.com/fusor/mig-agnosticd/issues/95
  checkout([$class: 'GitSCM', branches: [[name: 'development']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'agnosticd']], submoduleCfg: [], userRemoteConfigs: [[url: 'https://github.com/redhat-cop/agnosticd.git']]])

  checkout([$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'mig-agnosticd']], submoduleCfg: [], userRemoteConfigs: [[url: 'https://github.com/konveyor/mig-agnosticd.git']]])
  // Set agnosticd HOME and add to destroy script
  AGNOSTICD_HOME = "${env.WORKSPACE}/agnosticd"
  sh "echo 'export AGNOSTICD_HOME=${AGNOSTICD_HOME}' >> destroy_env.sh"
  
  withCredentials([
    string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
    string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY'),
    string(credentialsId: "$EC2_SUB_USER", variable: 'SUB_USER'),
    string(credentialsId: "$EC2_SUB_PASS", variable: 'SUB_PASS'),
    file(credentialsId: "${OCP4_PULL_SECRET}", variable: 'PULL_SECRET'),
    string(credentialsId: "$AGND_REPO", variable: 'OWN_REPO')
    ])
      {
        def pull_secret = readFile "${PULL_SECRET}"
        dir('mig-agnosticd') {

          def secret_vars = [
            'aws_access_key_id': "${AWS_ACCESS_KEY_ID}",
            'aws_secret_access_key': "${AWS_SECRET_ACCESS_KEY}",
            'redhat_registry_user': "${SUB_USER}",
            'redhat_registry_password': "${SUB_PASS}",
            'ocp4_token': "${pull_secret}",
            'own_repo_path': "${OWN_REPO}/{{ osrelease }}/"
          ]
        writeYaml file: 'secret.yml', data: secret_vars
        }
       sh "echo 'export AWS_REGION=${AWS_REGION} AWS_ACCESS_KEY=${AWS_ACCESS_KEY_ID} AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY}' >> destroy_env.sh"
     }
}

def prepare_workspace(src_version = '', dest_version = '') {
  // Prepare EC2 key for ansible consumption
  KEYS_DIR = "${env.WORKSPACE}" + '/keys'
  sh "mkdir -p ${KEYS_DIR}"
  sh "mkdir -p ${env.WORKSPACE}/kubeconfigs"
  
  // Define kubeconfig locations based on version of source and dest clusters
  if ("${src_version}" != '') {
    SOURCE_KUBECONFIG = "${env.WORKSPACE}/kubeconfigs/ocp-${src_version}-kubeconfig"
    
    if(src_version.startsWith("3.")) {
      SRC_IS_OCP3 = "true"
      echo "SRC_CLUSTER is OCP3: ${SRC_IS_OCP3}"
    } else {
      SRC_IS_OCP3 = "false"
    }
  }

  if ("${dest_version}" != '') {
    TARGET_KUBECONFIG = "${env.WORKSPACE}/kubeconfigs/ocp-${dest_version}-kubeconfig"
    
    if(dest_version.startsWith("3.")) {
      DEST_IS_OCP3 = "true"
      echo "DEST_CLUSTER is OCP3: ${DEST_IS_OCP3}"
    } else {
      DEST_IS_OCP3 = "false"
    }
  }
  OC_BINARY = "${env.WORKSPACE}/bin/oc"
  sh 'touch destroy_env.sh && chmod +x destroy_env.sh'
}

def prepare_persistent() {
  if (PERSISTENT) {
    sh "mkdir -p ${JENKINS_HOME}/persistent"
    sh "echo ${WORKSPACE} > ${JENKINS_HOME}/persistent/${CLUSTER_NAME}"
  }
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

/*
  parses the comment message and sets
  environment variables required for 
  the build based on comment text
 
  Supported comment patterns :
    \test                     : Use latest operator with current controller
    \test-with-operator #PR   : Use operator #PR with current controller
    \test-with-controller #PR : Use controller #PR with current operator
*/
def parse_comment_message(message) {
  def matched = message =~ /(.*)(test-with-(operator|controller) #?(\d+)|test)(.*)*/
  MIG_OPERATOR_BUILD_CUSTOM = false
  MIG_CONTROLLER_BUILD_CUSTOM = false
  
  // If comment pattern does not conform - 
  //     Fail the build
  //     Add comment to the PR mentioning the requester why build failed
  if (!matched) {
    def pr_handle = null
    def comment = "@user Illegal bot command. Setting build status to failed..."
    comment_on_pr(pr_handle, comment)
  }
  else {
    switch (message) {
      case ~/(.*)-with-operator(.*)/:
        MIG_OPERATOR_BUILD_CUSTOM = true
        MIG_CONTROLLER_BUILD_CUSTOM = false
        MIG_OPERATOR_PR_NO = matched[0][4]
        break

      case ~/(.*)-with-controller(.*)/:
        MIG_OPERATOR_BUILD_CUSTOM = false
        MIG_CONTROLLER_BUILD_CUSTOM = true
        MIG_CONTROLLER_PR_NO = matched[0][4]
        break
    }
  }
  echo "Building custom mig-operator? ${MIG_OPERATOR_BUILD_CUSTOM}"
  echo "Building custom mig-controller? ${MIG_CONTROLLER_BUILD_CUSTOM}"
}

/*
  Chekout PRs from given repo

  repo [string]      : name of repo
  pr_number [string] : pr to checkout
  directory [string] : target directory 
*/
def checkout_pr(repo, pr_number, directory) {
  checkout([$class: 'GitSCM', branches: [[name: 'FETCH_HEAD']], 
    doGenerateSubmoduleConfigurations: false, 
    extensions: [
      [$class: 'RelativeTargetDirectory', relativeTargetDir: directory]
    ], 
    submoduleCfg: [], 
    userRemoteConfigs: [
      [
        refspec: "+refs/pull/${pr_number}/head:refs/remotes/origin/PR-${pr_number}",
        url: "${repo}"
      ]
    ]
  ])
}

/*
  Comments given message on PR
  
  pr_handle [string] : Unknown atm
  message [string]   : Unknown atm
*/
def comment_on_pr(pr_handle, message) {
  // TODO : low prio
}

def teardown_ocp_agnosticd(cluster_version) {
  dir("mig-agnosticd/${cluster_version}") {
    withEnv([
      'PATH+EXTRA=~/.local/bin',
      'ANSIBLE_FORCE_COLOR=true',
      "AGNOSTICD_HOME=${AGNOSTICD_HOME}"]) {
         ansiColor('xterm') {
           if(cluster_version.startsWith("3.")) {
             sh './delete_ocp3_workshop.sh'
           } else {
             sh './delete_ocp4_workshop.sh'
           }
         }
    }
  }
}

def teardown_mig_controller(kubeconfig) {
  withEnv([ "KUBECONFIG=${kubeconfig}" ]) {
    ansiColor('xterm') {
      ansiblePlaybook(
        playbook: 'mig_controller_destroy.yml',
        hostKeyChecking: false,
        colorized: true)
    }
  }
}

def teardown_container_image() {
  // Check if is not upstream
  if (env.QUAYIO_CI_REPO && "${MIG_CONTROLLER_REPO}" != "https://github.com/konveyor/mig-controller.git") {
    ansiColor('xterm') {
      ansiblePlaybook(
        playbook: 'container_image_destroy.yml',
        hostKeyChecking: false,
        extras: "-e quayio_ci_repo=${QUAYIO_CI_REPO} -e quayio_ci_tag=${MIG_CONTROLLER_BRANCH}",
        colorized: true)
    }
  }
}

def teardown_s3_bucket() {
  withCredentials([
    string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
    string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY')
    ]) {
    ansiColor('xterm') {
      ansiblePlaybook(
        playbook: 's3_bucket_destroy.yml',
        hostKeyChecking: false,
        colorized: true)
    }
  }
}

def teardown_e2e(kubeconfig) {
  dir('mig-e2e') {
    withEnv([ "KUBECONFIG=${kubeconfig}" ]) {
      ansiColor('xterm') {
        ansiblePlaybook(
          playbook: 'e2e_destroy_all.yml',
          hostKeyChecking: false,
          colorized: true)
      }
    }
  }
}

def teardown_e2e_stuck_ns(kubeconfig) {
  withEnv([ "KUBECONFIG=${kubeconfig}" ]) {
    ansiColor('xterm') {
      ansiblePlaybook(
        playbook: 'ocp_ns_destroy.yml',
        hostKeyChecking: false,
        colorized: true)
    }
  }
}

def teardown_e2e_purge_pv(kubeconfig) {
  withEnv([ "KUBECONFIG=${kubeconfig}" ]) {
    ansiColor('xterm') {
      ansiblePlaybook(
        playbook: 'ocp_pv_purge.yml',
        hostKeyChecking: false,
        colorized: true)
    }
  }
}

def run_debug(kubeconfig) {
  withEnv([ "KUBECONFIG=${kubeconfig}" ]) {
    sh "${DEBUG_SCRIPT} ${DEBUG_SCRIPT_ARGS} || true"
  }
}

return this
