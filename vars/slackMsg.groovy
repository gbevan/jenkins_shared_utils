#!/usr/bin/env groovy

def call(String status = 'STARTED', String msg = '') {
  out.println('IN slackMsg')
  def color = 'danger'
  if (status == 'STARTED') {
    color = '#FFFF00'
  } else if (status == 'SUCCESS' || status == 'VERIFIED' || status == 'DEPLOYED') {
    color = 'good'
  }

  slackSend(color: color, message: "${status}: Job ${currentBuild.fullDisplayName} branch: ${env.BRANCH_NAME}: ${msg}")
}
