#!/usr/bin/env groovy

/**
 * Tags the git repo with version and releases the docker image and exported
 * tar file to Artifactory.
 *
 * releaseDockerImage(
 *   gitCredId: 'jenkins-cred-id',
 *   version: 'version-to-tag-and-release',
 *   approvers: 'user1,user2,users-who-can-approve-release',
 *   onlyBranch: 'master',
 *   dockerRepo: 'slm-docker',
 *   dockerPort: '85',
 *   imageName: 'hello-world:latest',
 *   tarArtFolder: 'SLM/HelloWord'
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
  def tarArtFolder = parameters.get('tarArtFolder', '')

  when (BRANCH_NAME == 'master') {
    def deploy = true

    try {
      timeout(time: waitForMins, unit: 'MINUTES') {
        input message: "Git Tag and Release Image v${version} to Artifactory?", ok: "Apply", submitter: "${approvers}"
      }
    } catch(errInp) {
      deploy = false
    }

    if (deploy) {
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
          git describe --tags --exact-match "${GIT_ORIGIN_COMMIT}" 2>/dev/null | grep -E "^[0-9]" || echo "NO_VERSION_TAG"
          ''',
        returnStdout: true
        ).trim()
        echo "CURRENT_GIT_TAG=${CURRENT_GIT_TAG}"
        echo "version=${env.version}"

        if (CURRENT_GIT_TAG != version) {
          //////////////////////////
          // Tag release in GitHub
          sh(
            script: '''
              echo git tag "${version}" "${GIT_ORIGIN_COMMIT}"
              echo git push "${GITURLWITHCREDS}" "${version}"
            '''
          )
        }
      }

      ////////////////////////////////////////////
      // retag docker image for remote registry
      def imgToPush = "docker.dxc.com:${dockerPort}/${imageName}:${version}"
      sh "docker tag ${imageName}:${version} ${imgToPush}"

      ////////////////////////////////////////////
      // Release to artifactory docker registry
      docker.withRegistry('https://docker.dxc.com:80', 'slmartifactory') {
        def img = docker.image(imgToPush)
        img.push()
      }

      ////////////////////////////////////
      // Export docker image to tar
      sh(script: "docker save ${imageName}:${version} | bzip2 > /images/releases/${imageName}-${version}.tar.bz2")

      ////////////////////////////////////
      // Upload tar to artifactory
      def aServer = Artifactory.server 'slmartifactory'
      def uploadSpec = """{
        "files": [
          {
            "pattern": "/images/releases/${imageName}-${version}.tar.bz2",
            "target": "${tarArtFolder}/"
          }
        ]
      }"""
      aServer.upload(uploadSpec)

      // TODO: cleanup docker image

      body()  // callback to caller pipeline
    }
  }
}
