#!/usr/bin/env groovy

/**
 * releaseDockerImage(
 *   gitCredId: 'jenkins-cred-id',
 *   version: 'version-to-tag-and-release',
 *   approvers: 'user1,user2,users-who-can-approve-release',
 *   onlyBranch: 'master'
 * )
 */
def call(Map parameters) {

  def gitCredId = parameters.get('gitCredId', '')
  def version = parameters.get('version', '')
  def approvers = parameters.get('approvers', '')
  def onlyBranch = parameters.get('onlyBranch', 'master')
  def waitForMins = parameters.get('waitForMins', 10)

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

        sh(
          script: '''
            echo git tag "${BLD_TAG}" "${GIT_ORIGIN_COMMIT}"
            echo git push "${GITURLWITHCREDS}" "${BLD_TAG}"
          '''
        )
      }

      // sh(script: "docker save sshproxy:${sshproxy.version} | bzip2 > /images/nightlies/sshproxy-${sshproxy.version}.tar.bz2")
      //slackMsg status: 'DEPLOYED', msg: "Git Tagged ${BLD_TAG}, docker image exported ok"
    }
  // }
  //////////////////////////////////////////////////
}
