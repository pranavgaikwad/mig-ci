properties([
parameters([
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: 'ci_ec2_key', description: 'EC2 private key needed to access instances, from Jenkins credentials store', name: 'EC2_PRIV_KEY', required: true),
string(defaultValue: 'ci', description: 'EC2 SSH key name for remote access', name: 'EC2_KEY', trim: false),
string(defaultValue: 'HEAD', description: 'CPMA branch to checkout', name: 'CPMA_BRANCH', trim: false),
string(defaultValue: 'https://github.com/fusor/cpma.git', description: 'CPMA repo to clone', name: 'CPMA_REPO', trim: false),
string(description: 'Cluster hostname for ssh access', name: 'CPMA_HOSTNAME', trim: false),
string(description: 'Cluster currnet-context master name to generate report from', name: 'CPMA_CLUSTERNAME', trim: false),
string(description: 'Login for the cluster', name: 'CPMA_LOGIN', trim: false),
string(description: 'Password for the cluster', name: 'CPMA_PASSWD', trim: false),
string(defaultValue: 'root', description: 'SSH login', name: 'CPMA_SSHLOGIN', trim: false),
string(defaultValue: '22', description: 'SSH port', name: 'CPMA_SSHPORT', trim: false),
booleanParam(defaultValue: true, description: 'Clean up workspace after build', name: 'CLEAN_WORKSPACE')])])

node {
    def goroot = tool name: 'go 1.12.7', type: 'go'

    try {
        stage('Setup CPMA') {
          checkout([$class: 'GitSCM', branches: [[name: "${CPMA_BRANCH}"]], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], userRemoteConfigs: [[url: "${CPMA_REPO}"]]])

          CPMA_SSHPRIVATEKEY = "${WORKSPACE}/${EC2_KEY}.pem"
          withCredentials([file(credentialsId: "${env.EC2_PRIV_KEY}", variable: "SSH_PRIV_KEY")]) {
            sh "cat ${SSH_PRIV_KEY} > ${CPMA_SSHPRIVATEKEY}"
            sh "chmod 600 ${CPMA_SSHPRIVATEKEY}"
          }
        }

        stage('Run e2e') {
          withEnv([
              "GOROOT=${goroot}",
              "PATH+GO=${goroot}/bin",
              "KUBECONFIG=${WORKSPACE}/kube.conf",
              "CPMA_SSHPRIVATEKEY=${CPMA_SSHPRIVATEKEY}"]) {
            sh 'make e2e'
          }
        }

    } catch (Exception ex) {
        currentBuild.result = "FAILED"
        println(ex.toString())
    } finally {
      if (CLEAN_WORKSPACE) {
        cleanWs cleanWhenFailure: false, notFailBuild: true
      }
    }
}
