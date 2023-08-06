#!/usr/bin/env groovy
import com.ms3_inc.tavros.jenkins.Utilities

def call(Map args = [:]) {
    def utils = new Utilities(this)
    pipeline {
        agent {
            kubernetes {
                yaml '''
                    apiVersion: v1
                    kind: Pod
                    spec:
                      containers:
                      - name: git
                        image: atlassian/default-image:4.20230726
                        command:
                        - sleep
                        args:
                        - infinity
                      - name: maven
                        image: maven:3.8.1-jdk-11-slim
                        securityContext:
                          runAsUser: 1000
                        command: ["/bin/sh", "-c"]
                        args:
                        - tail -f /dev/null
                      - name: kaniko
                        image: gcr.io/kaniko-project/executor:debug
                        command:
                        - sleep
                        args:
                        - 9999999
                        volumeMounts:
                        - name: kaniko-secret
                          mountPath: /kaniko/.docker
                      volumes:
                      - name: kaniko-secret
                        secret:
                            secretName: tavros-artifacts-registry
                            items:
                            - key: .dockerconfigjson
                              path: config.json
                '''
                defaultContainer 'maven'
            }
        }
        environment {
            FQDN = """${sh(
                    returnStdout: true,
                    script: 'mvn help:evaluate -Dexpression=registry.host -q -DforceStdout'
            )}"""
            VERSION = """${sh(
                    returnStdout: true,
                    script: 'mvn help:evaluate -Dexpression=project.version -q -DforceStdout'
            )}"""
            NAME = """${sh(
                    returnStdout: true,
                    script: 'mvn help:evaluate -Dexpression=project.artifactId -q -DforceStdout'
            )}"""
        }
        stages {
            stage('Test/Build') {
                environment {
                    NEXUS_CREDS = credentials("${TAVROS_REG_CREDS}")
                    FQDN = "${TAVROS_FQDN}"
                }
                steps {
                    script {
                        sh 'mvn clean package'
                    }
                }
            }
            stage('Push with Kaniko') {
                steps {
                    container('kaniko') {
                        sh '''
                        echo "Running kaniko cmd"
                        ls
                        /kaniko/executor -f `pwd`/Dockerfile -c `pwd` --destination="registry.${FQDN}/${NAME}:${VERSION}"
                        '''
                    }
                }
            }
            stage('Update Helm Release') {
                environment {
                    GIT_CREDS = credentials("${TAVROS_GIT_CREDS}")
                    GIT_HOST = "${TAVROS_GIT_HOST}"
                }
                steps {
                    container('git') {
                        dir("tavros-platform") {
                            checkout([
                                    $class           : 'GitSCM',
                                    branches         : [[name: '*/main']],
                                    extensions       : [[$class: 'LocalBranch', localBranch: "**"]],
                                    userRemoteConfigs: [[
                                                                credentialsId: "${TAVROS_GIT_CREDS}",
                                                                url          : "https://${TAVROS_GIT_HOST}/tavros/platform.git"
                                                        ]]
                            ])
                        }

                        script {
                            if (env.BUILD_USER_EMAIL == null) {
                                env.BUILD_USER_EMAIL = ""
                                env.BUILD_USER = "Jenkins"
                            }

                            utils.shResource "update-helm-release.sh"
                        }
                    }
                }
            }
        }
    }
}
