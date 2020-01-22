// common_stages.groovy
def deploy_ocp4_agnosticd(kubeconfig, cluster_version) {

  def repo_version = "4.1.0" // Must map to a valid version in `own_repo_path:`
  def short_version = cluster_version.replace(".", "")

  // Even for nightly releases, osrelease must map to a valid 4.x value on agnosticd repos (clientvm/bastion req)
  def releases = [
    '4.1': "4.1.13",
    '4.2': "4.2.0",
    'latest-4.1': "4.1.0",
    'latest-4.2': "4.1.0",
    'latest': "4.1.0",
    'nightly': "4.1.0",
  ]
  def osrelease = releases["${cluster_version}"]
  def full_cluster_name = ''
  def cluster_adm_user = ''
  def cluster_adm_passwd = ''

  if (PERSISTENT) {
    echo "Cluster is a persistent build"
    full_cluster_name = "${CLUSTER_NAME}-${short_version}"
  } else {
    echo "Cluster is not a persistent build"
    full_cluster_name = "${CLUSTER_NAME}-${short_version}-${BUILD_NUMBER}"
  }

  echo "Cluster name is : ${full_cluster_name}"

  def console_addr = "https://api.cluster-${full_cluster_name}.${full_cluster_name}${BASESUFFIX}:6443"

  withCredentials([
    [$class: 'UsernamePasswordMultiBinding', credentialsId: "${OCP4_CREDENTIALS}", usernameVariable: 'OCP4_ADMIN_USER', passwordVariable: 'OCP4_ADMIN_PASSWD']]) {
    cluster_adm_user = "${OCP4_ADMIN_USER}"
    cluster_adm_passwd = "${OCP4_ADMIN_PASSWD}"
  }

  def CORRECT_OCP4_RELEASE = "${osrelease}"
  if (!cluster_version.startsWith("4.")) {
     CORRECT_OCP4_RELEASE = "${cluster_version}"
  }

  if ("${cluster_version}" == "nightly" 
     || "${cluster_version}" == "latest" 
     || "${cluster_version}" == "latest-4.1" 
     || "${cluster_version}" == "latest-4.2"
     ) {
    echo "Cluster is a ${cluster_version} build"
    // Fetch and dump OCP4 nightly release data
    withEnv(["OCP4_RELEASE=${CORRECT_OCP4_RELEASE}"]){
      ansiColor('xterm') {
        ansiblePlaybook(
          playbook: 'ocp4_dump_release.yml',
          hostKeyChecking: false,
          unbuffered: true,
          colorized: true)
      }
    }
  }

  def OLM_TEXT = ' using non-OLM'
  if (USE_OLM) {
    OLM_TEXT = ' using OLM'
  }
  sh "mkdir olm"
  sh "cp -R mig-agnosticd/4.x mig-agnosticd/${cluster_version}"
  sh "echo 'cd mig-agnosticd/${cluster_version} && ./delete_ocp4_workshop.sh &' >> destroy_env.sh"
  return {
    stage('Deploy agnosticd OCP workshop ' + cluster_version + OLM_TEXT) {
      steps_finished << 'Deploy agnosticd OCP workshop ' + cluster_version + OLM_TEXT

        dir("olm") {
          def olm_vars = [
            'olm_cluster_version': "${cluster_version}"
          ]
          writeYaml file: 'olm_vars.yml', data: olm_vars
        }

        dir("mig-agnosticd/${cluster_version}") {
          def my_vars = [
            'email': "${EMAIL}",
            'guid': "${full_cluster_name}",
            'output_dir': "${WORKSPACE}/.agnosticd/{{ guid }}",
            'subdomain_base_suffix': "${BASESUFFIX}",
            'HostedZoneId': "${HOSTZONEID}",
            'key_name': "${EC2_KEY}",
            'cloud_provider': "ec2",
            'aws_region': "${AWS_REGION}",
            'ansible_ssh_private_key_file': "${PRIVATE_KEY}"
          ]
          writeYaml file: 'my_vars.yml', data: my_vars

          // Apply extra tags as list
          def readContent = readFile 'my_vars.yml'
          writeFile file: 'my_vars.yml', text: readContent+"cloud_tags:\n- owner: \"{{ email }}\"\n"

          sh 'mv ocp4_vars.yml ocp4_vars.yml.orig'

          def ocp4_vars = [
            'cloudformation_retries': "0",
            'env_type': "ocp4-workshop",
            'software_to_deploy': "none",
            'ocp4_installer_version': "${osrelease}",
            'osrelease': "${repo_version}",
            'repo_version': "${repo_version}",
            'install_ocp4': "true",
            'install_opentlc_integration': "false",
            'install_idm': "htpasswd",
            'install_ipa_client': "false",
            'default_workloads': '[]',
            'bastion_instance_type': "t2.medium",
            'clientvm_instance_type': "t2.medium",
            'master_instance_type': "${OCP4_MASTER_INSTANCE_TYPE}",
            'worker_instance_type': "${OCP4_WORKER_INSTANCE_TYPE}",
            '_infra_node_instance_type': "${OCP4_INFRA_INSTANCE_TYPE}",
            'clientvm_instance_count': "1",
            'master_instance_count': "${OCP4_MASTER_INSTANCE_COUNT}",
            'worker_instance_count': "${OCP4_WORKER_INSTANCE_COUNT}",
            'admin_user': "${cluster_adm_user}",
            'archive_dir': "{{ output_dir | dirname }}/archive"
          ]
          writeYaml file: 'ocp4_vars.yml', data: ocp4_vars

          // Add extra vars for nightly builds
          if ("${cluster_version}" == "nightly"
             || "${cluster_version}" == "latest" 
             || "${cluster_version}" == "latest-4.1" 
             || "${cluster_version}" == "latest-4.2"
             ) {
            def ocp4_data = readYaml file: 'ocp4_vars.yml'
            def ocp4_dumped_data = readYaml file: "${WORKSPACE}/ocp4_release.yml"
            ocp4_data.ocp4_installer_use_dev_preview = "true"
            ocp4_data.ocp4_installer_url = ocp4_dumped_data.ocp4_installer_url
            ocp4_data.ocp4_client_url = ocp4_dumped_data.ocp4_client_url
            sh 'rm -f ocp4_vars.yml'
            writeYaml file: 'ocp4_vars.yml', data: ocp4_data
          }
          
          withEnv([
          'PATH+EXTRA=~/.local/bin',
          "AGNOSTICD_HOME=${AGNOSTICD_HOME}",
          "OCP4_RELEASE=${CORRECT_OCP4_RELEASE}",
          'ANSIBLE_FORCE_COLOR=true'])
          {
            ansiColor('xterm') {
              sh './create_ocp4_workshop.sh'
            }
          }
        }

        def login_vars = [
        "console_addr": "${console_addr}",
        "user": "${cluster_adm_user}",
        "passwd": "${cluster_adm_passwd}",
        "kubeconfig": "${kubeconfig}"
        ]

        login_vars = login_vars.collect { e -> '-e ' + e.key + '=' + e.value }
        ansiColor('xterm') {
          ansiblePlaybook(
            playbook: 'login.yml',
            extras: "${login_vars.join(' ')}",
            hostKeyChecking: false,
            unbuffered: true,
            colorized: true)
        }
      }
  }
}

def deploy_ocp3_agnosticd(kubeconfig, cluster_version) {
  
  def repo_version = "${cluster_version}"
  def short_version = cluster_version.replace(".", "")
  def releases = [
    '3.7': "3.7.119",
    '3.9': "3.9.99",
    '3.10': "3.10.34",
    '3.11': "3.11.129"
  ]
  def osrelease = releases["${repo_version}"]
  def full_cluster_name = ''
  def cluster_adm_user = ''
  def cluster_adm_passwd = ''

  if (PERSISTENT) {
    echo "Cluster is a persistent build"
    full_cluster_name = "${CLUSTER_NAME}-${short_version}"
  } else {
    echo "Cluster is not a persistent build"
    full_cluster_name = "${CLUSTER_NAME}-${short_version}-${BUILD_NUMBER}"
  }

  echo "Cluster name is : ${full_cluster_name}"

  def console_addr = "https://master.${full_cluster_name}${BASESUFFIX}:443"

  withCredentials([
    [$class: 'UsernamePasswordMultiBinding', credentialsId: "${OCP3_CREDENTIALS}", usernameVariable: 'OCP3_ADMIN_USER', passwordVariable: 'OCP3_ADMIN_PASSWD']]) {
    cluster_adm_user = "${OCP3_ADMIN_USER}"
    cluster_adm_passwd = "${OCP3_ADMIN_PASSWD}"
  }

  sh "cp -R mig-agnosticd/3.x mig-agnosticd/${cluster_version}"
  sh "echo 'cd mig-agnosticd/${cluster_version} && ./delete_ocp3_workshop.sh &' >> destroy_env.sh"
  return {
    stage('Deploy agnosticd OCP workshop ' + cluster_version) {
      steps_finished << 'Deploy agnosticd OCP workshop ' + cluster_version

        dir("mig-agnosticd/${cluster_version}") {
          def my_vars = [
            'email': "${EMAIL}",
            'guid': "${full_cluster_name}",
            'output_dir': "${WORKSPACE}/.agnosticd/{{ guid }}",
            'subdomain_base_suffix': "${BASESUFFIX}",
            'HostedZoneId': "${HOSTZONEID}",
            'key_name': "${EC2_KEY}",
            'cloud_provider': "ec2",
            'aws_region': "${AWS_REGION}",
            'ansible_ssh_private_key_file': "${PRIVATE_KEY}",
            'install_glusterfs': "${OCP3_GLUSTERFS}"
          ]
          writeYaml file: 'my_vars.yml', data: my_vars

          // Apply extra tags as list
          def readContent = readFile 'my_vars.yml'
          writeFile file: 'my_vars.yml', text: readContent+"cloud_tags:\n- owner: \"{{ email }}\"\n"
    
          sh 'mv ocp3_vars.yml ocp3_vars.yml.orig' 

          def ocp3_vars = [
            'env_type': "ocp-workshop",
            'repo_version': "${repo_version}",
            'osrelease': "${osrelease}",
            'software_to_deploy': "openshift",
            'course_name': "ocp-workshop",
            'platform': "aws",
            'install_k8s_modules': "true",
            'update_packages': "${OCP3_UPDATE}",
            'bastion_instance_type': "t2.large",
            'master_instance_type': "${OCP3_MASTER_INSTANCE_TYPE}",
            'infranode_instance_type': "${OCP3_INFRA_INSTANCE_TYPE}",
            'node_instance_type': "${OCP3_WORKER_INSTANCE_TYPE}",
            'support_instance_type': "m4.large",
            'node_instance_count': "${OCP3_WORKER_INSTANCE_COUNT}",
            'master_instance_count': "${OCP3_MASTER_INSTANCE_COUNT}",
            'infranode_instance_count': "${OCP3_INFRA_INSTANCE_COUNT}",
            'support_instance_public_dns': "true",
            'nfs_server_address': "support1.{{ guid }}{{ subdomain_base_suffix }}",
            'nfs_exports_config': "*(insecure,rw,no_root_squash,no_wdelay,sync)",
            'archive_dir': "{{ output_dir | dirname }}/archive",
            'admin_user': "${cluster_adm_user}"
          ]
          writeYaml file: 'ocp3_vars.yml', data: ocp3_vars

          withEnv([
          'PATH+EXTRA=~/.local/bin',
          "AGNOSTICD_HOME=${AGNOSTICD_HOME}",
          'ANSIBLE_FORCE_COLOR=true'])
          {
            ansiColor('xterm') {
              sh './create_ocp3_workshop.sh'
            }
          }
        }

        def login_vars = [
        "console_addr": "${console_addr}",
        "user": "${cluster_adm_user}",
        "passwd": "${cluster_adm_passwd}",
        "kubeconfig": "${kubeconfig}"
        ]

        login_vars = login_vars.collect { e -> '-e ' + e.key + '=' + e.value }
        ansiColor('xterm') {
          ansiblePlaybook(
            playbook: 'login.yml',
            extras: "${login_vars.join(' ')}",
            hostKeyChecking: false,
            unbuffered: true,
            colorized: true)
        }
    } 
  }
}

def deploy_ceph(cluster_version) {
  def short_version = cluster_version.tokenize(".")[0]
  if (cluster_version == "nightly") {
    short_version = '4'
  }
  return {
    if (CEPH) {
      stage('Deploy CEPH workload on ' + cluster_version) {
        steps_finished << 'Deploy CEPH workload on ' + cluster_version
        sh 'sleep 180'
        dir("mig-agnosticd/workloads") {
          withEnv([
            "AGNOSTICD_HOME=${AGNOSTICD_HOME}",
            'ANSIBLE_FORCE_COLOR=true'])
          {
            ansiColor('xterm') {
                sh "./deploy_workload.sh -a create -w ceph -v ${short_version} -m ${WORKSPACE}/mig-agnosticd/${cluster_version}"
            }
          }
        }
      }
    }
  }
}

def deploy_ocp4(kubeconfig, cluster_version) {
  def osrelease = ""
  switch(cluster_version) {
    case ['v4.1', '4.1', 'latest-4.1']:
      osrelease = "latest-4.1"
      break
    case ['v4.2', '4.2', 'latest-4.2', 'latest']:
      osrelease = "latest-4.2"
      break
    case [ 'v4.3', '4.3', 'nightly']:
      osrelease = "nightly"
      break
    default:
      osrelease = "${cluster_version}"
      break
  }

  def short_version = cluster_version.replace(".", "")
  def full_cluster_name = ''

  if (PERSISTENT) {
    echo "Cluster is a persistent build"
    full_cluster_name = "${CLUSTER_NAME}-${short_version}"
  } else {
    echo "Cluster is not a persistent build"
    full_cluster_name = "${CLUSTER_NAME}-${short_version}-${BUILD_NUMBER}"
  }

  echo "Cluster name is : ${full_cluster_name}"

  sh "echo './openshift-install destroy cluster &' >> destroy_env.sh"
  return {
    stage('Deploy OCP cluster ' + cluster_version) {
      steps_finished << 'Deploy OCP cluster ' + cluster_version + " (" + osrelease + ")"
      withCredentials([
          string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
          string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY'),
          [$class: 'UsernamePasswordMultiBinding', credentialsId: "${OCP4_CREDENTIALS}", usernameVariable: 'OCP4_ADMIN_USER', passwordVariable: 'OCP4_ADMIN_PASSWD']
          ])
      {
        echo "OCP4 kubeconfig set to : ${kubeconfig}"
        // Ensure DEST_CLUSTER_VERSION is set for single cluster deployments
        withEnv(["KUBECONFIG=${kubeconfig}", "CLUSTER_NAME=${full_cluster_name}", "OCP4_RELEASE=${osrelease}", "DEST_CLUSTER_VERSION=${cluster_version}"]){
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

def sanity_checks(kubeconfig) {
  return {
    stage('Run OCP3 Sanity Checks') {
      steps_finished << 'Run OCP3 Sanity Checks'
      withEnv(["KUBECONFIG=${kubeconfig}"]) {
        ansiColor('xterm') {
          ansiblePlaybook(
            playbook: 'ocp_sanity_check.yml',
            extras: "-e oc_binary=oc",
            hostKeyChecking: false,
            unbuffered: true,
            colorized: true)
        }
      }
    }
  }
}

def login_cluster(
  cluster_url,
  cluster_user,
  cluster_password,
  cluster_version,
  kubeconfig) {

  return {
    stage("Login to OCP ${cluster_version}") {
      steps_finished << 'Login to OCP ' + cluster_version
      def ocp_login_vars = [
      "console_addr": "${cluster_url}",
      "user": "${cluster_user}",
      "passwd": "${cluster_password}",
      "kubeconfig": "${kubeconfig}",
      "force_login": "true"
      ]
     sh 'rm -f ocp_login_vars.yml'
     writeYaml file: 'ocp_login_vars.yml', data: ocp_login_vars
     ocp_login_vars = ocp_login_vars.collect { e -> '-e ' + e.key + '=' + e.value }
     ansiColor('xterm') {
       ansiblePlaybook(
         playbook: 'login.yml',
         extras: "${ocp_login_vars.join(' ')}",
         hostKeyChecking: false,
         unbuffered: true,
         colorized: true)
        }
    }
  }
}

def deploy_mig_controller_on_both(
  source_kubeconfig,
  target_kubeconfig,
  mig_controller_src,
  mig_controller_dst) {
  // mig_controller_src boolean defines if the source cluster will host mig controller
  // mig_controller_dst boolean defines if the destination cluster will host mig controller
  sh "echo 'ansible-playbook s3_bucket_destroy.yml &' >> destroy_env.sh"
  return {
    stage('Build mig-controller image and deploy on both clusters') {
      steps_finished << 'Build mig-controller image and deploy on both clusters'
      // Create custom mig-controller docker image if building a different mig-controller repo/branch
      if ("${MIG_CONTROLLER_REPO}" != "https://github.com/fusor/mig-controller.git") {
        withEnv(["IMG=${QUAYIO_CI_REPO}:${MIG_CONTROLLER_BRANCH}"]) {
          dir('mig-controller') {
            sh 'make docker-build'
          }
        }
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${QUAYIO_CREDENTIALS}", usernameVariable: 'QUAY_USERNAME', passwordVariable: 'QUAY_PASSWORD']]) {
          sh 'docker login quay.io -u $QUAY_USERNAME -p $QUAY_PASSWORD'
        }
        withEnv(["IMG=${QUAYIO_CI_REPO}:${MIG_CONTROLLER_BRANCH}"]) {
          sh 'docker push $IMG'
        }
        // Update mig-controller image and version to custom build or assume default
        mig_controller_img = "${QUAYIO_CI_REPO}"
        mig_controller_tag = "${MIG_CONTROLLER_BRANCH}"
      } else {
          mig_controller_img = "quay.io/ocpmigrate/mig-controller"
          mig_controller_tag = "${MIG_CONTROLLER_BRANCH}"
      }

      def SRC_USE_OLM = false
      def DEST_USE_OLM = false
      if (USE_OLM) {
        if (!SRC_CLUSTER_VERSION.startsWith("3.")) {
          SRC_USE_OLM = true
        }
        if (!DEST_CLUSTER_VERSION.startsWith("3.")) {
          DEST_USE_OLM = true
        }
      } 
      withCredentials([
        string(credentialsId: "$EC2_SUB_USER", variable: 'SUB_USER'),
        string(credentialsId: "$EC2_SUB_PASS", variable: 'SUB_PASS')])
      {
      // Source
      withEnv([
          "KUBECONFIG=${source_kubeconfig}",
          "MIG_OPERATOR_USE_OLM=${SRC_USE_OLM}",
          "MIG_OPERATOR_USE_DOWNSTREAM=${USE_DOWNSTREAM}",
          "SUB_USER=${SUB_USER}",
          "SUB_PASS=${SUB_PASS}",
          "PATH+EXTRA=~/bin"]) {
        ansiColor('xterm') {
          ansiblePlaybook(
            playbook: 'mig_controller_deploy.yml',
            extras: "-e mig_controller_host_cluster=${mig_controller_src} -e mig_controller_ui=false",
            hostKeyChecking: false,
            unbuffered: true,
            colorized: true)
        }
      }
      // Target
      withEnv([
          "KUBECONFIG=${target_kubeconfig}",
          "MIG_OPERATOR_USE_OLM=${DEST_USE_OLM}",
          "MIG_OPERATOR_USE_DOWNSTREAM=${USE_DOWNSTREAM}",
          "SUB_USER=${SUB_USER}",
          "SUB_PASS=${SUB_PASS}",
          "PATH+EXTRA=~/bin"]) {
        ansiColor('xterm') {
          ansiblePlaybook(
            playbook: 'mig_controller_deploy.yml',
            extras: "-e mig_controller_host_cluster=${mig_controller_dst} -e mig_controller_ui=false",
            hostKeyChecking: false,
            unbuffered: true,
            colorized: true)
        }
      }
    }
    }
  }
}


def execute_migration(e2e_tests, source_kubeconfig, target_kubeconfig) {
  return {
    stage('Execute migration') {
      steps_finished << 'Execute migration'
      sh "cp -r config/mig_controller.yml mig-e2e/config"

      for (int i = 0; i < e2e_tests.size(); i++) {
        steps_finished << 'Execute test ' + e2e_tests[i]
        dir('mig-e2e') {
          withEnv([
            "KUBECONFIG=${source_kubeconfig}",
            "PATH+EXTRA=~/bin"]) {
            ansiColor('xterm') {
              ansiblePlaybook(
                playbook: "${env.E2E_PLAY}",
                hostKeyChecking: false,
                extras: "-e 'with_migrate=false'",
                tags: "${e2e_tests[i]}",
                unbuffered: true,
                colorized: true)
            }
          }
          withEnv([
            "KUBECONFIG=${target_kubeconfig}",
            "PATH+EXTRA=~/bin"]) {
            ansiColor('xterm') {
              ansiblePlaybook(
                playbook: "${env.E2E_PLAY}",
                hostKeyChecking: false,
                extras: "-e 'with_deploy=false'",
                tags: "${e2e_tests[i]}",
                unbuffered: true,
                colorized: true)
            }
          }
        }
      }
    }
  }
}


return this
