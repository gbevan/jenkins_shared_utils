#!/usr/bin/env groovy

/**
 * releaseDockerImage(
 *   gitCredId: 'jenkins-cred-id',
 *   version: 'version-to-tag-and-release',
 *   approvers: 'user1,user2,users-who-can-approve-release',
 *   onlyBranch: 'master',
 *   dockerRepo: 'slm-docker',
 *   dockerPort: '85',
 *   imageName: 'hello-world:latest'
 * ) { stmts to run on completion of release... }
 */
def call(Map parameters, body) {

  def gitCredId = parameters.get('gitCredId', '')
  def version = parameters.get('version', '')
  def approvers = parameters.get('approvers', '')
  def onlyBranch = parameters.get('onlyBranch', 'master')
  def waitForMins = parameters.get('waitForMins', 10)
  def dockerRepo = parameters.get('dockerRepo', 'slm-docker')
  def dockerPort = parameters.get('dockerPort', '85')
  def imageName = parameters.get('imageName', '')

  echo "in releaseDockerImage()"

  //////////////////////////////////////////////////
  // when (BRANCH_NAME == 'master') {
    def deploy = true
    try {
      timeout(time: waitForMins, unit: 'MINUTES') {
        input message: "Git Tag and Release Image v${version} to Artifactory?", ok: "Apply", submitter: "${approvers}"
      }
    } catch(errInp) {
      deploy = false
    }
    if (deploy) {
      env.GIT_ORIGIN_COMMIT = sh (
        script: 'git rev-parse refs/remotes/origin/${BRANCH_NAME}',
        returnStdout: true
      ).trim()
      echo "GIT_ORIGIN_COMMIT=${env.GIT_ORIGIN_COMMIT}"

      env.GIT_URL = sh(returnStdout: true, script: 'git config remote.origin.url').trim()
      echo "GIT_URL=${env.GIT_URL}"

      withCredentials ([
        usernameColonPassword(credentialsId: gitCredId, variable: 'GITUSERPASS')
      ]) {
        env.GITURLWITHCREDS = sh(
          returnStdout: true,
          script: 'echo "${GIT_URL}" | sed -e "s!://!://${GITUSERPASS}@!"'
        ).trim()
        CURRENT_GIT_TAG = sh (
          script: '''
          git fetch --tags "${GITURLWITHCREDS}"
          git describe --tags --exact-match "${GIT_ORIGIN_COMMIT}" 2>/dev/null | grep -E "^[0-9]" || echo "NO_VERSION_TAG"
          ''',
        returnStdout: true
        ).trim()
        echo "CURRENT_GIT_TAG=${CURRENT_GIT_TAG}"

        env.BLD_TAG = "${version}"
        echo "BLD_TAG=${env.BLD_TAG}"

        // Tag release in GitHub
        sh(
          script: '''
            echo git tag "${BLD_TAG}" "${GIT_ORIGIN_COMMIT}"
            echo git push "${GITURLWITHCREDS}" "${BLD_TAG}"
          '''
        )
      }

      // retag docker image for remote registry
      sh "docker tag ${imageName}:${version} docker.dxc.com:${dockerPort}/${dockerRepo}/${imageName}:${version}"

      // sh(script: "docker save sshproxy:${sshproxy.version} | bzip2 > /images/nightlies/sshproxy-${sshproxy.version}.tar.bz2")

      // Release docker image to registry
      def aServer = Artifactory.server 'slmartifactory-ads-docker'
      // def aHost = "tcp://docker.dxc.com:${dockerPort}"
      // echo "aHost: ${aHost}"
      def aDocker = Artifactory.docker server: aServer
      def aDockerInfo = aDocker.push "docker.dxc.com:${dockerPort}/${dockerRepo}/${imageName}:${version}", dockerRepo
      aDockerServer.publishBuildInfo aDockerInfo

      // TODO: cleanup docker image

      body()
    }
  // }
  //////////////////////////////////////////////////
}
