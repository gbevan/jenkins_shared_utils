#!/usr/bin/env groovy

def call(Map parameters) {

  def status = parameters.get('status', 'STARTED')
  def msg = parameters.get('msg', '')
  def branch = scm.branches[0].name
  // println("slackMsg status ${status}, msg ${msg}")

  def color = 'danger'
  if (status =~ /^STARTED/) {
    color = '#FFFF00'
  // } else if (status == 'SUCCESS' || status == 'VERIFIED' ||
  //            status == 'DEPLOYED' || status == 'PASSED' ||
  //            status == 'BUILT') {
  } else if (status =~ /^(SUCCESS|VERIFIED|DEPLOYED|PASSED|BUILT|SKIPPED)/) {
    color = 'good'
  }

  def combinedMsg = "${status}: Job ${currentBuild.fullDisplayName} branch: ${branch}: ${msg}"
  if (env.SLACK_SUPPRESS != "1")
    slackSend(color: color, message: combinedMsg)
  // else {
  }
  echo combinedMsg
}
