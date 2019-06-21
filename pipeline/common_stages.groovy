// common_stages.groovy

def deployOCP3_OA(prefix = '') {
  if (prefix != '') {
    prefix = "-e prefix=${prefix}"
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
        withEnv(['PATH+EXTRA=~/bin', "KUBECONFIG=${KUBECONFIG_TMP}"]) {
          echo "Region: ${env.AWS_REGION}"
          echo "Name: ${env.CLUSTER_NAME}"
          echo "Version: ${env.OCP3_VERSION}"
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
  return {
    stage('Deploy OCP3 cluster with agnosticd') {
      steps_finished << 'Deploy agnosticd OCP3 workload ' + OCP3_VERSION
      dir('agnosticd') {
        withCredentials([
            string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
            string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY'),
            string(credentialsId: "$EC2_SUB_USER", variable: 'SUB_USER'),
            string(credentialsId: "$EC2_SUB_PASS", variable: 'SUB_PASS'),
            string(credentialsId: "$AGND_REPO", variable: 'OWN_REPO')
            ])
        {
          def vars = [
            "-e 'guid=${GUID}'",
            "-e 'env_type=${envtype}'",
            "-e 'own_repo_path=${OWN_REPO}/${osrelease}'",
            "-e 'osrelease=${osrelease}'",
            "-e 'repo_version=${repo_version}'",
            "-e 'cloud_provider=ec2'",
            "-e 'aws_region=${AWS_REGION}'",
            "-e 'HostedZoneId=${HOSTZONEID}'",
            "-e 'key_name=${EC2_KEY}'",
            "-e 'ansible_ssh_private_key_file=${PRIVATE_KEY}'",
            "-e 'subdomain_base_suffix=${BASESUFFIX}'",
            "-e 'node_instance_count=${NODE_COUNT}'",
            "-e 'software_to_deploy=openshift'",
            "-e 'redhat_registry_user=${SUB_USER}'",
            "-e 'redhat_registry_password=${SUB_PASS}'",
            "-e 'aws_access_key_id=${AWS_ACCESS_KEY_ID}'",
            "-e 'aws_secret_access_key=${AWS_SECRET_ACCESS_KEY}'",
            "-e 'email=${EMAIL}' -e'output_dir=${WORKSPACE}'",
            // User admin with admin password to preserve consistency between different deployment types
            "-e 'admin_user=admin",
            "-e 'admin_user_password=admin",
            // Fix for commit # 8780932 to work
            "-e 'course_name=ocp-workshop' -e 'platform=aws'",
            // Workaround for hitting AWS limit of c4.xlarge instances
            "-e 'bastion_instance_type=t2.large' -e 'master_instance_type=c4.xlarge'",
            "-e 'infranode_instance_type=c4.4xlarge' -e 'node_instance_type=c4.4xlarge'"
          ]
          withEnv(['PATH+EXTRA=~/.local/bin']) {
            sh "which aws"
            echo "Region: ${AWS_REGION}"
            echo "Name: ${GUID}"
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
            "KUBECONFIG=${KUBECONFIG_TMP}"])
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


def login_agnosticd() {
  def credentials = [
    "console_addr=https://master.${GUID}${BASESUFFIX}:443",
    "user=admin",
    "passwd=admin"
  ]
  return {
    stage('Login into cluster') {
      steps_finished << 'Login into cluster ' + OCP3_VERSION
      withEnv([
          'PATH+EXTRA=~/bin',
          "KUBECONFIG=${KUBECONFIG_TMP}"])
        {
        ansiColor('xterm') {
          ansiblePlaybook(
            playbook: 'login.yml',
            extras: "${credentials.join(' ')}",
            hostKeyChecking: false,
            unbuffered: true,
            colorized: true)
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
        withEnv([
            'PATH+EXTRA=~/bin',
            "KUBECONFIG=${KUBECONFIG_TMP}"])
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


def sanity_checks() {
  return {
    stage('Run OCP3 Sanity Checks') {
      steps_finished << 'Run OCP3 Sanity Checks'
      withEnv([
          'PATH+EXTRA=~/bin',
          "KUBECONFIG=${KUBECONFIG_TMP}"]) {
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


def teardown_OCP3_OA(prefix = '') {
  if (prefix != '') {
    prefix = "-e prefix=${prefix}"
  }
  if (EC2_TERMINATE_INSTANCES) {
    withCredentials([
        string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
        string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY'),
        ]) 
    {
      withEnv(['PATH+EXTRA=~/bin']) {
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
          "-e 'aws_region=${AWS_REGION}'",
          "-e 'guid=${GUID}'",
          "-e 'env_type=ocp-workshop'",
          "-e 'cloud_provider=ec2'",
          "-e 'aws_access_key_id=${AWS_ACCESS_KEY_ID}'",
          "-e 'aws_secret_access_key=${AWS_SECRET_ACCESS_KEY}'"
        ]
        withEnv(['PATH+EXTRA=~/.local/bin']) {
          echo "Teardown_vars: ${teardown_vars.join(' ')}"
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
