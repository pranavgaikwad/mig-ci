// common_stages.groovy
def deploy_ocp4_agnosticd(kubeconfig, cluster_version) {

  def repo_version = "4.5.0" // Must map to a valid version in `own_repo_path:`
  def short_version = cluster_version.replace(".", "")

  // GA OCP releases
  // ocp4_installer_version instructs agnosticd the directory containing the installer/client on https://mirror.openshift.com/pub/openshift-v4/clients/ocp/
  def releases = [
    '4.1': "4.1.41",
    '4.2': "4.2.36",
    '4.3': "4.3.40",
    '4.4': "4.4.31",
    '4.5': "4.5.23",
    '4.6': "4.6.8",
  ]
  def ocp4_installer_version = releases["${cluster_version}"]
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

  // Non-GA (nightlies and other special releases)
  if (!cluster_version.startsWith("4.")) {
    echo "Cluster is a ${cluster_version} build"
    // Fetch and dump target OCP4 release
    withEnv(["OCP4_RELEASE=${cluster_version}"]){
      ansiColor('xterm') {
        ansiblePlaybook(
          playbook: 'ocp4_dump_release.yml',
          hostKeyChecking: false,
          colorized: true)
      }
    }
    // Reset ocp4_installer_version to 4.1.0 for ocp-preview releases, it will be ignored by agnosticd
    ocp4_installer_version = '4.1.0'
  }

  def OLM_TEXT = ' using non-OLM'
  if (USE_OLM) {
    OLM_TEXT = ' using OLM'
  }
  sh "mkdir olm"
  sh "cp -R mig-agnosticd/4.x mig-agnosticd/${cluster_version}"
  sh "echo 'cd ${WORKSPACE}/mig-agnosticd/${cluster_version} && ./delete_ocp4_workshop.sh &' >> destroy_env.sh"
  return {
    stage('Deploy agnosticd OCP workshop ' + cluster_version + OLM_TEXT) {
      steps_finished << 'Deploy agnosticd OCP workshop ' + cluster_version + OLM_TEXT

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
            'ocp4_installer_version': "${ocp4_installer_version}",
            'osrelease': "${repo_version}",
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
          if (!cluster_version.startsWith("4.")) {
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
    '3.10': "3.10.181",
    '3.11': "3.11.161"
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
  sh "echo 'cd ${WORKSPACE}/mig-agnosticd/${cluster_version} && ./delete_ocp3_workshop.sh &' >> destroy_env.sh"
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
            colorized: true)
        }
    } 
  }
}

def deploy_workload(workload,cluster_version,status) {
  def short_version = cluster_version.tokenize(".")[0]
  if (!cluster_version.startsWith("3.")) {
    short_version = '4'
  }
  return {
    if (status == 'true') {
      stage("Deploy ${workload} workload on ${cluster_version}") {
        steps_finished << 'Deploy ${workload} workload on ' + cluster_version
        sh 'sleep 180'
        dir("mig-agnosticd/workloads") {
          withEnv([
            "AGNOSTICD_HOME=${AGNOSTICD_HOME}",
            'ANSIBLE_FORCE_COLOR=true'])
          {
            ansiColor('xterm') {
                sh "./deploy_workload.sh -a create -w ${workload} -v ${short_version} -m ${WORKSPACE}/mig-agnosticd/${cluster_version}"
            }
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
         colorized: true)
        }
    }
  }
}

def cam_disconnected(
  kubeconfig,
  cam_disconnected_config,
  cluster_version,
  validate_node_config) {
  return {
    stage('Deploy CAM disconnected ' + cluster_version) {
      steps_finished << 'Deploy CAM disconnected on OCP ' + cluster_version
      withCredentials([
        [$class: 'UsernamePasswordMultiBinding', credentialsId: "${STAGE_REGISTRY_CREDENTIALS}", usernameVariable: 'SUB_STAGE_USER', passwordVariable: 'SUB_STAGE_PASS'],
        string(credentialsId: "${EC2_SUB_USER}", variable: 'SUB_USER'),
        string(credentialsId: "${EC2_SUB_PASS}", variable: 'SUB_PASS'),
        string(credentialsId: "${QUAY_TOKEN}", variable: 'QUAY_TOKEN'),
        string(credentialsId: "${CAM_DISCONNECTED_REPO}", variable: 'CAM_DISCONNECTED_REPO')]) {
          withEnv([
            "KUBECONFIG=${kubeconfig}",
            "CAM_DISCONNECTED_CONFIG=${cam_disconnected_config}"]) {
             ansiColor('xterm') {
               ansiblePlaybook(
                 playbook: 'cam_disconnected_prepare.yml',
                 extras: "",
                 hostKeyChecking: false,
                 colorized: true)
             }
             ansiColor('xterm') {
               ansiblePlaybook(
                 playbook: 'cam_disconnected_run.yml',
                 extras: "-e validate_node_config=${validate_node_config}",
                 hostKeyChecking: false,
                 colorized: true)
             }
          }
      }
    }
  }
}

/*
  Builds mig-controller image, pushes to Quay
  Sets environment variables with build details
*/
def build_mig_controller() {
  return {
    stage('Build mig-controller image') {
      steps_finished << 'Build mig-controller image'
      MIG_CONTROLLER_BUILD_CUSTOM = true
      
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
      MIG_CONTROLLER_IMAGE = "${QUAYIO_CI_REPO}"
      MIG_CONTROLLER_TAG = "${MIG_CONTROLLER_BRANCH}"
    }
  }
}

/*
  Deploys mig-controller on given cluster

  kubeconfig [string]     : Kubeconfig location
  is_host [string]        : Is this a host cluster?
  cluster_version [string]: Current cluster version
*/
def deploy_mig_controller(kubeconfig, is_host, cluster_version) {
  return {
    def type = !is_host ? 'source' : 'destination'
    stage("Deploy mig-controller on ${type} cluster") {
      steps_finished << "Deploy mig-controller on ${type} cluster"
      
      SHOULD_USE_OLM = USE_OLM && !cluster_version.startsWith("3.")

      def mig_controller_image_args = MIG_CONTROLLER_BUILD_CUSTOM ?
        "-e mig_controller_image=${MIG_CONTROLLER_IMAGE} -e mig_controller_version=${MIG_CONTROLLER_TAG}" : ""

      def mig_controller_deployment_args = is_host ?
        "-e mig_controller_host_cluster='true' -e mig_controller_ui=${MIG_CONTROLLER_UI}" : 
        "-e mig_controller_host_cluster='false'"

      withCredentials([
        string(credentialsId: "$EC2_SUB_USER", variable: 'SUB_USER'),
        string(credentialsId: "$EC2_SUB_PASS", variable: 'SUB_PASS')]) {
        withEnv([
            "KUBECONFIG=${kubeconfig}",
            "MIG_CONTROLLER_BUILD_CUSTOM=${MIG_CONTROLLER_BUILD_CUSTOM}",
            "MIG_OPERATOR_USE_OLM=${SHOULD_USE_OLM}",
            "SUB_USER=${SUB_USER}",
            "SUB_PASS=${SUB_PASS}",
            "PATH+EXTRA=~/bin"]) {
          ansiColor('xterm') {
            ansiblePlaybook(
              playbook: 'mig_controller_deploy.yml',
              extras: "${mig_controller_image_args} ${mig_controller_deployment_args}",
              hostKeyChecking: false,
              colorized: true)
          }
        }
      }
    }
  }
}

/*
  Deploys mig-operator on given cluster

  kubeconfig [string]     : Kubeconfig location
  is_host [string]        : Is this a host cluster?
  cluster_version [string]: Current cluster version
*/
def deploy_mig_operator(kubeconfig, is_host, cluster_version) {
  return {
    def type = !is_host ? 'source' : 'destination'
    stage("Deploy mig-operator on ${type} cluster") {
      steps_finished << "Deploy mig-operator on ${type} cluster"
      
      SHOULD_USE_OLM = USE_OLM && !cluster_version.startsWith("3.")

      withCredentials([
        string(credentialsId: "$EC2_SUB_USER", variable: 'SUB_USER'),
        string(credentialsId: "$EC2_SUB_PASS", variable: 'SUB_PASS')])
      {
        // Target
        withEnv([
            "KUBECONFIG=${kubeconfig}",
            "MIG_OPERATOR_BUILD_CUSTOM=${MIG_OPERATOR_BUILD_CUSTOM}",
            "MIG_OPERATOR_USE_OLM=${SHOULD_USE_OLM}",
            "MIG_OPERATOR_USE_DOWNSTREAM=${USE_DOWNSTREAM}",
            "MIG_OPERATOR_USE_DISCONNECTED=${USE_DISCONNECTED}",
            "SUB_USER=${SUB_USER}",
            "SUB_PASS=${SUB_PASS}",
            "PATH+EXTRA=~/bin"]) {
          ansiColor('xterm') {
            ansiblePlaybook(
              playbook: 'mig_operator_deploy.yml',
              hostKeyChecking: false,
              colorized: true)
          }
        }
      }
    }
  }
}

/*
  Builds operator images, creates Quay
  application, pushes application
*/
def build_mig_operator() {    
  return {
    directory = 'mig-operator'
    
    stage('Build mig-operator image') {
      steps_finished << 'Build mig-operator image'
      withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${QUAYIO_CREDENTIALS}", usernameVariable: 'QUAY_USERNAME', passwordVariable: 'QUAY_PASSWORD']]) {
        withEnv([
          "CONTAINER_IMG=${QUAYIO_CI_REPO_OPERATOR}:PR-${MIG_OPERATOR_PR_NO}", 
          "TAG=${MIG_OPERATOR_PR_NO}",
          "BUNDLE_IMG=${QUAYIO_CI_REPO_OPERATOR_BUNDLE}:PR-${MIG_OPERATOR_PR_NO}",
          "INDEX_IMG=${QUAYIO_CI_REPO_OPERATOR_INDEX}:PR-${MIG_OPERATOR_PR_NO}"]) {
          dir(directory) {
            sh 'docker login quay.io -u $QUAY_USERNAME -p $QUAY_PASSWORD'
            // update metadata
            sh "find ./deploy/olm-catalog/bundle/manifests -name '*.clusterserviceversion.*' -exec sed -E -i -e 's,image: quay.io/(.*)/mig-operator-container:(.*),image: ${CONTAINER_IMG},g' {} \\;"
            sh "find ./deploy/non-olm/ -name '*operator.yml*' -exec sed -E -i -e 's,image: quay.io/(.*)/mig-operator-container:(.*),image: ${CONTAINER_IMG},g' {} \\;"

            // build and push bundle image
            sh 'docker build -t $BUNDLE_IMG -f build/Dockerfile.bundle .'
            sh 'docker push $BUNDLE_IMG'
            // build and push index image
            sh 'opm index add --container-tool docker --bundles $BUNDLE_IMG --tag $INDEX_IMG'
            sh 'docker push $INDEX_IMG'
            sh 'opm index export --container-tool docker -i $INDEX_IMG konveyor-operator'

            // push application
            sh """#!/bin/bash +x
              last_version=\$(curl -s https://quay.io/cnr/api/v1/packages?namespace=konveyor_ci | jq '.[] | select(.name==\"konveyor_ci/mtc-operator\") | .default')
              last_patch=\$(echo \$last_version | sed -e 's/\"//g' | cut -d. -f3)
              let current_ver=\$last_patch+1
              AUTH_TOKEN=\$(curl -sH \"Content-Type: application/json\" -XPOST https://quay.io/cnr/api/v1/users/login -d \
              '{\"user\": {\"username\": \"${QUAY_USERNAME}\", \"password\": \"${QUAY_PASSWORD}\"}}' | jq -r '.token')
              ${MIG_CI_OPERATOR_COURIER_BINARY} --verbose push ./downloaded/mtc-operator konveyor_ci mtc-operator 0.0.\${current_ver} "\$AUTH_TOKEN"
            """

            // create catalogsource
            sh """#!/bin/bash +x
              sed -E -i -e 's,image: quay.io/(.*)/mig-operator-index:(.*),image: ${INDEX_IMG},g' ./mig-operator-bundle.yaml
              sed -E -i -e 's,name: (.*),name: konveyor-ci-operators,g' ./mig-operator-bundle.yaml
            """ 
          }
        }
      }
    }
  }
}

def execute_migration(e2e_tests, source_kubeconfig, target_kubeconfig, extra_args=null) {
  return {
    stage('Execute migration') {
      sh "cp -r config/mig_controller.yml mig-e2e/config"
      for (int i = 0; i < e2e_tests.size(); i++) {
        steps_finished << 'Execute migration with test cases : ' + e2e_tests[i]
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
                colorized: true)
            }
          }

          // Prepare clusters
          if (E2E_DEPLOY_ONLY) {
            withEnv([
              "KUBECONFIG=${target_kubeconfig}",
              "PATH+EXTRA=~/bin"]) {
              ansiColor('xterm') {
                ansiblePlaybook(
                  playbook: "e2e_prepare_clusters.yml",
                  hostKeyChecking: false,
                  extras: "",
                  colorized: true)
              }
            }
          }

          if (!E2E_DEPLOY_ONLY) {
            try {
              withEnv([
                "KUBECONFIG=${target_kubeconfig}",
                "PATH+EXTRA=~/bin"]) {
                ansiColor('xterm') {
                  ansiblePlaybook(
                    playbook: "${env.E2E_PLAY}",
                    hostKeyChecking: false,
                    extras: "-e 'with_deploy=false'",
                    tags: "${e2e_tests[i]}",
                    colorized: true)
                }
              }
            } catch (Exception ex) {
              withEnv(["KUBECONFIG=${TARGET_KUBECONFIG}"]) {
                dir("${WORKSPACE}") {
                  sh "mkdir must-gather"
                  sh "${OC_BINARY} adm must-gather --image=quay.io/konveyor/must-gather:latest --dest-dir=./must-gather"
                  sh """#!/bin/bash +x
                  aws s3 sync ./must-gather/ s3://mig-ci-build-artifacts-do-not-delete/${JOB_NAME}/${BUILD_NUMBER}/
                  """
                  MUST_GATHER_LINK=sh(
                    script: "aws s3 presign s3://mig-ci-build-artifacts-do-not-delete/${JOB_NAME}/${BUILD_NUMBER}/",
                    returnStdout: true
                  )
                }
              }
              error "Migration test case ${e2e_tests[i]} failed"
            }

          }
        }
      }
    }
  }
}


return this
