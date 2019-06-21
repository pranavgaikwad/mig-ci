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
          withEnv(['PATH+EXTRA=~/bin', "KUBECONFIG=${kubeconfig}"]) {
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



def deployOCP3_OA(prefix = '') {
  if (prefix != '') {
    prefix = "-e prefix=${prefix}"
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
        withEnv(['PATH+EXTRA=~/bin', "KUBECONFIG=${KUBECONFIG_TMP}"]) {
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

def deploy_ocp3_agnosticd() {
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
  def console_addr = "https://master.${CLUSTER_NAME}-${BUILD_NUMBER}${BASESUFFIX}:443"
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
            'guid': "${CLUSTER_NAME}-${BUILD_NUMBER}",
            'env_type': "${envtype}",
            'own_repo_path': "${OWN_REPO}/${osrelease}",
            'osrelease': "${osrelease}",
            'repo_version': "${repo_version}",
            'cloud_provider': 'ec2',
            'aws_region': "${AWS_REGION}",
            'HostedZoneId': "${HOSTZONEID}",
            'key_name': "${EC2_KEY}",
            'ansible_ssh_private_key_file': "${PRIVATE_KEY}",
            'subdomain_base_suffix': "${BASESUFFIX}",
            'node_instance_count': "${NODE_COUNT}",
            'master_instance_count': "${MASTER_COUNT}",
            'software_to_deploy': 'openshift',
            'redhat_registry_user': "${SUB_USER}",
            'redhat_registry_password': "${SUB_PASS}",
            'aws_access_key_id': "${AWS_ACCESS_KEY_ID}",
            'aws_secret_access_key': "${AWS_SECRET_ACCESS_KEY}",
            'email': "${EMAIL}",
            'output_dir': "${WORKSPACE}",
            "update_packages": "false",
            // User admin to preserve consistency between different deployment types
            'admin_user': "${cluster_adm_user}",
            // Fix for commit # 8780932 to work
            'course_name': 'ocp-workshop',
            'platform': 'aws'
          ]
          sh 'rm -f vars.yml'
          writeYaml file: 'vars.yml', data: vars
          vars = vars.collect { e -> '-e ' + e.key + '=' + e.value }

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
          "passwd": "r3dh4t1!",
          "source_kubecnfig": "${KUBECONFIG_TMP}",
          "target_kubeconfig": "${KUBECONFIG_OCP3}"
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

def deployOCP4() {
  return {
    stage('Deploy OCP4 cluster') {
      steps_finished << 'Deploy OCP4'
      withCredentials([
          string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
          string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY')
          ])
      {
        withEnv(["KUBECONFIG=${KUBECONFIG_TMP}"]){
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


def provision_pvs() {
  return {
    stage('Provison PVs on source cluster') {
      steps_finished << 'Provison PVs on source cluster'
      withCredentials([
        string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
        string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY')
        ])
      {
        withEnv(["KUBECONFIG=${KUBECONFIG_OCP3}"]) {
          ansiColor('xterm') {
            ansiblePlaybook(
              playbook: 'nfs_provision_pvs.yml',
              hostKeyChecking: false,
              unbuffered: true,
              colorized: true)
          }
        }
      }
    }
  }
}


def prepare_test_data(kubeconfig) {
  return {
    stage('Prepare test data on source cluster') {
      steps_finished << 'Prepare test data on source cluster'
      withEnv(['PATH+EXTRA=~/bin', "KUBECONFIG=${kubeconfig}"]) {
        dir('ocp-mig-test-data') {
          ansiColor('xterm') {
            ansiblePlaybook(
              playbook: 'mysql-pvc.yml',
              extras: "-e with_backup=false -e with_restore=false",
              hostKeyChecking: false,
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
      withEnv([
          'PATH+EXTRA=~/bin',
          "KUBECONFIG=${kubeconfig}"]) {
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


return this
