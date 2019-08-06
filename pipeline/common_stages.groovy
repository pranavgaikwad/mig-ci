// common_stages.groovy


def deploy_origin3_dev(kubeconfig) {
  return {
    stage('Deploy OCP3 origin3-dev cluster') {
      steps_finished << 'Deploy OCP3 origin3-dev cluster ' + OCP3_VERSION
      withCredentials([
        string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
        string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY')
        ])
      {
        dir('origin3-dev') {
          withEnv([
            "PATH+EXTRA=~/bin",
            "KUBECONFIG=${kubeconfig}"]) {
            ansiColor('xterm') {
              ansiblePlaybook(
                playbook: 'deploy.yml',
                extras: "-e @overrides.yml",
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



def deployOCP3_OA(kubeconfig, prefix = '') {
  if (prefix != '') {
    prefix = "-e prefix=${prefix}"
  }
  withCredentials([
      string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
      string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY')
      ])
  {
    sh "echo 'export AWS_REGION=${AWS_REGION} AWS_ACCESS_KEY=${AWS_ACCESS_KEY_ID} AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY}' >> destroy_env.sh"
    sh "echo 'ansible-playbook destroy_ocp3_cluster.yml ${prefix} &' >> destroy_env.sh"
  }
  return {
    stage('Deploy OCP3 OA cluster') {
      steps_finished << 'Deploy OCP3 OA cluster ' + OCP3_VERSION
      withCredentials([
          string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
          string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY'),
          string(credentialsId: "$EC2_SUB_USER", variable: 'SUB_USER'),
          string(credentialsId: "$EC2_SUB_PASS", variable: 'SUB_PASS')
          ])
      {
        withEnv(["KUBECONFIG=${kubeconfig}"]) {
          ansiColor('xterm') {
            ansiblePlaybook(
              playbook: 'deploy_ocp3_cluster.yml',
              extras: "${prefix}",
              hostKeyChecking: false,
              unbuffered: true,
              colorized: true)
          }
        }
      }
    }
  }
}

def deploy_ocp3_agnosticd(kubeconfig) {
  def repo_version = "${OCP3_VERSION.substring(1)}"
  def releases = [
    '3.7': "3.7.23",
    '3.9': "3.9.51",
    '3.10': "3.10.34",
    '3.11': "3.11.104"
  ]
  def osrelease = releases["${repo_version}"]
  def envtype = "ocp-workshop"
  def cluster_adm_user = 'admin'
  def console_addr = "https://master.${CLUSTER_NAME}-v3-${BUILD_NUMBER}${BASESUFFIX}:443"
  sh "echo 'cd agnosticd && ansible-playbook ansible/configs/ocp-workshop/destroy_env.yml -e @teardown_vars.yml &' >> destroy_env.sh && echo 'cd -' >> destroy_env.sh"
  return {
    stage('Deploy OCP3 cluster with agnosticd') {
      steps_finished << 'Deploy agnosticd OCP3 workshop ' + OCP3_VERSION
      withCredentials([
          string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
          string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY'),
          string(credentialsId: "$EC2_SUB_USER", variable: 'SUB_USER'),
          string(credentialsId: "$EC2_SUB_PASS", variable: 'SUB_PASS'),
          string(credentialsId: "$AGND_REPO", variable: 'OWN_REPO')
          ])
      {
        dir('agnosticd') {
          def vars = [
            'guid': "${CLUSTER_NAME}-v3-${BUILD_NUMBER}",
            'env_type': "${envtype}",
            'own_repo_path': "${OWN_REPO}/${osrelease}",
            'osrelease': "${osrelease}",
            'repo_version': "${repo_version}",
            'cloud_provider': 'ec2',
            'bastion_instance_type': 't2.large',
            'master_instance_type': "${OCP3_MASTER_INSTANCE_TYPE}",
            'infranode_instance_type': 'm5.large',
            'node_instance_type': "${OCP3_WORKER_INSTANCE_TYPE}",
            'support_instance_type': 'm4.large',
            'aws_region': "${AWS_REGION}",
            'HostedZoneId': "${HOSTZONEID}",
            'key_name': "${EC2_KEY}",
            'ansible_ssh_private_key_file': "${PRIVATE_KEY}",
            'subdomain_base_suffix': "${BASESUFFIX}",
            'node_instance_count': "${OCP3_WORKER_INSTANCE_COUNT}",
            'master_instance_count': "${OCP3_MASTER_INSTANCE_COUNT}",
            'software_to_deploy': 'openshift',
            'redhat_registry_user': "${SUB_USER}",
            'redhat_registry_password': "${SUB_PASS}",
            'aws_access_key_id': "${AWS_ACCESS_KEY_ID}",
            'aws_secret_access_key': "${AWS_SECRET_ACCESS_KEY}",
            'email': "${EMAIL}",
            'output_dir': "${WORKSPACE}",
            "update_packages": "false",
            "support_instance_public_dns": "true",
            "nfs_server_address": "support1.${CLUSTER_NAME}-v3-${BUILD_NUMBER}${BASESUFFIX}",
            // User admin to preserve consistency between different deployment types
            'admin_user': "${cluster_adm_user}",
            // Fix for commit # 8780932 to work
            'course_name': 'ocp-workshop',
            'platform': 'aws'
          ]
          sh 'rm -f vars.yml'
          writeYaml file: 'vars.yml', data: vars
          vars = vars.collect { e -> '-e ' + e.key + '=' + e.value }

          // Dump teardown wars on host
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
            echo "Region: ${AWS_REGION}"
            echo "Name: ${CLUSTER_NAME}"
            echo "Version: ${OCP3_VERSION}"
            ansiColor('xterm') {
              ansiblePlaybook(
                playbook: 'ansible/main.yml',
                extras: "${vars.join(' ')}",
                hostKeyChecking: false,
                skippedTags: 'validate_cf_template, generate_env_keys',
                unbuffered: true,
                colorized: true)
            }
          }
        }

        def login_vars = [
          "console_addr": "${console_addr}",
          "user": "${cluster_adm_user}",
          "passwd": "r3dh4t1!", // This value is not configurable
          "kubeconfig": "${kubeconfig}"
        ]
        sh 'rm -f login_vars.yml'
        writeYaml file: 'login_vars.yml', data: login_vars

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
}

def deployOCP4(kubeconfig) {
  sh "echo './openshift-install destroy cluster &' >> destroy_env.sh"
  return {
    stage('Deploy OCP4 cluster') {
      steps_finished << 'Deploy OCP4 ' + OCP4_VERSION
      withCredentials([
          string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
          string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY'),
          [$class: 'UsernamePasswordMultiBinding', credentialsId: "${OCP4_CREDENTIALS}", usernameVariable: 'OCP4_ADMIN_USER', passwordVariable: 'OCP4_ADMIN_PASSWD']
          ])
      {
        withEnv(["KUBECONFIG=${kubeconfig}", "CLUSTER_NAME=${CLUSTER_NAME}-v4-${BUILD_NUMBER}"]){
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


def deploy_NFS(prefix = '') {
  if (prefix != '') {
    prefix = "-e prefix=${prefix}"
  }
  withCredentials([
      string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
      string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY')
      ])
  {
    sh "echo 'export AWS_REGION=${AWS_REGION} AWS_ACCESS_KEY=${AWS_ACCESS_KEY_ID} AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY}' >> destroy_env.sh"
    sh "echo 'ansible-playbook nfs_server_destroy.yml ${prefix} &' >> destroy_env.sh"
  }
  return {
    stage('Configure NFS storage') {
      steps_finished << 'Configure NFS storage'
      ansiColor('xterm') {
        ansiblePlaybook(
          playbook: 'nfs_server_deploy.yml',
          hostKeyChecking: false,
          extras: "${prefix}",
          unbuffered: true,
          colorized: true)
      }
    }
  }
}


def provision_pvs(kubeconfig, prefix = '') {
  if (prefix != '') {
    prefix = "-e prefix=${prefix}"
  }
  return {
    stage('Provision PVs on source cluster') {
      steps_finished << 'Provision PVs on source cluster'
      withCredentials([
        string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
        string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY')
        ])
      {
        withEnv(["KUBECONFIG=${kubeconfig}"]) {
          ansiColor('xterm') {
            ansiblePlaybook(
              playbook: 'nfs_provision_pvs.yml',
              hostKeyChecking: false,
              extras: "${prefix}",
              unbuffered: true,
              colorized: true)
          }
        }
      }
    }
  }
}


def load_sample_data(kubeconfig) {
  return {
    stage('Load Sample Data/Apps on OCP3') {
      steps_finished << 'Load Sample Data/Apps on OCP3'
      dir('ocp-mig-test-data') {
        withEnv([
            'PATH+EXTRA=~/bin',
            "KUBECONFIG=${kubeconfig}"])
          {
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


def sanity_checks(kubeconfig) {
  return {
    stage('Run OCP3 Sanity Checks') {
      steps_finished << 'Run OCP3 Sanity Checks'
      withEnv(["KUBECONFIG=${kubeconfig}"]) {
        ansiColor('xterm') {
          ansiblePlaybook(
            playbook: 'ocp_sanity_check.yml',
            hostKeyChecking: false,
            extras: "-e oc_binary=/var/lib/jenkins/bin/oc",
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
  // mig_controller_src boolean defines if the source cluster (OCP3) will host mig controller
  // mig_controller_dst boolean defines if the destination cluster (OCP4) will host mig controller
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
        mig_controller_version = "${MIG_CONTROLLER_BRANCH}"
      } else {
          mig_controller_img = "quay.io/ocpmigrate/mig-controller"
          mig_controller_version = "latest"
      }

      // Source (OCP3)
      withEnv([
          "KUBECONFIG=${source_kubeconfig}",
          "MIG_CONTROLLER_IMG=${mig_controller_img}",
          "MIG_CONTROLLER_VERSION=${mig_controller_version}",
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
      // Target (OCP4)
      withEnv([
          "KUBECONFIG=${target_kubeconfig}",
          "MIG_CONTROLLER_IMG=${mig_controller_img}",
          "MIG_CONTROLLER_VERSION=${mig_controller_version}",
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
                playbook: 'mig_controller_samples.yml',
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
                playbook: 'mig_controller_samples.yml',
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
