#!/usr/bin/env groovy

import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

/**
 * Tags the git repo with version and releases the docker image and exported
 * tar file to Artifactory.
 *
 * releaseDockerImage(
 *   gitCredId: 'jenkins-cred-id',
 *   version: 'version-to-tag-and-release',
 *   approvers: 'user1,user2,users-who-can-approve-release',
 *   onlyBranch: 'master',
 *   dockerRepo: 'my-docker',
 *   dockerPort: '85',
 *   imageName: 'hello-world:latest',
 *   tarArtFolder: 'MyFolder/HelloWord'
 * ) { stmts to run on completion of release... }
 */
def call(Map parameters, body) {

  def gitCredId = parameters.get('gitCredId', '')
  def version = parameters.get('version', '')
  def releaseVersion = parameters.get('releaseVersion', version)
  def gitTagIsReleaseVersion = parameters.get('gitTagIsReleaseVersion', true)
  def approvers = parameters.get('approvers', '')
  def onlyBranch = parameters.get('onlyBranch', 'master')
  def waitForMins = parameters.get('waitForMins', 10)
  def dockerRepo = parameters.get('dockerRepo', 'ads-docker')
  def dockerPort = parameters.get('dockerPort', '80')
  def imageName = parameters.get('imageName', '')
  def tarArtFolder = parameters.get('tarArtFolder', '')
  def artHost = parameters.get('artHost', '')
  def artCredId = parameters.get('artCredId', '')
  def artServerId = parameters.get('artServerId', '')

  when (BRANCH_NAME == 'master') {
    def deploy = false

    /////////////////////////
    // Get the commit point
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

      //////////////////////////////////////
      // Get any current tag at this commit
      CURRENT_GIT_TAG = sh (
        script: '''
        git fetch --tags "${GITURLWITHCREDS}"
        git describe --tags --exact-match "${GIT_ORIGIN_COMMIT}" 2>/dev/null | grep -E "^[a-zA-Z0-9]" || echo "NO_VERSION_TAG"
        ''',
      returnStdout: true
      ).trim()

      echo "CURRENT_GIT_TAG=${CURRENT_GIT_TAG}"
      env.VERSION = version
      echo "version=${env.version}"

      if ((!gitTagIsReleaseVersion && CURRENT_GIT_TAG != version)) {
        body("SKIPPED (Not Yet Tagged for Release: ${CURRENT_GIT_TAG} != ${version})") // callback to calling pipeline
      } else if ((gitTagIsReleaseVersion && CURRENT_GIT_TAG != releaseVersion)) {
        body("SKIPPED (Not Yet Tagged for Release: ${CURRENT_GIT_TAG} != ${releaseVersion})") // callback to calling pipeline
      } else {
        deploy = true
      }
    }

    if (!deploy) {
      Utils.markStageSkippedForConditional(STAGE_NAME)
      return
    }

    try {
      timeout(time: waitForMins, unit: 'MINUTES') {
        input message: "This commit has been tagged. Release Image ${releaseVersion} to Artifactory?", ok: "Apply", submitter: "${approvers}"
      }
    } catch(errInp) {
      deploy = false
      body("SKIPPED Aborted by admin or timeout")
      Utils.markStageSkippedForConditional(STAGE_NAME)
      return
    }

    if (deploy) {
      ////////////////////////////////////////////
      // retag docker image for remote registry
      sh "docker images"
      def imgToPush = "${artHost}:${dockerPort}/${imageName}:${releaseVersion}"
      sh "docker tag ${imageName}:${version} ${imgToPush}"
      if (releaseVersion != version) {
        sh "docker tag ${imageName}:${version} ${imageName}:${releaseVersion}"
      }

      ////////////////////////////////////////////
      // Release to artifactory docker registry
      docker.withRegistry("https://${artHost}:${dockerPort}", "${artCredId}") {
        def img = docker.image(imgToPush)
        img.push()
      }

      ////////////////////////////////////
      // Export docker image to tar
      sh(script: "docker save ${imageName}:${releaseVersion} | bzip2 > /images/releases/${imageName}-${releaseVersion}.tar.bz2")

      ////////////////////////////////////
      // Upload tar to artifactory
      def aServer = Artifactory.server "${artServerId}"
      def uploadSpec = """{
        "files": [
          {
            "pattern": "/images/releases/${imageName}-${releaseVersion}.tar.bz2",
            "target": "${tarArtFolder}/"
          }
        ]
      }"""
      aServer.upload(uploadSpec)

      /////////////////////////
      // Cleanup docker image
      sh "docker rmi --force ${imgToPush}"
      sh "docker rmi --force ${imageName}:${version}"
      if (releaseVersion != version) {
        sh "docker rmi --force ${imageName}:${releaseVersion}"
      }

      body('DEPLOYED to Artifactory')  // callback to calling pipeline
    }
  }
}
