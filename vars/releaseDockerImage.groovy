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
  def onlyBranch = parameters.get('onlyBranch', '')

  echo "in releaseDockerImage()"
}
