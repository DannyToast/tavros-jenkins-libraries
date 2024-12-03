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
                      - name: maven
                        image: maven:3.6.3-jdk-11
                        securityContext:
                          runAsUser: 1000
                        command:
                        - sleep
                        args:
                        - infinity
                      - name: kaniko
                        image: gcr.io/kaniko-project/executor:v1.13.0-debug
                        command:
                        - sleep
                        args:
                        - 9999999
                        volumeMounts:
                            - name: kaniko-secret
                              mountPath: /kaniko/.docker/
                      volumes:
                      - name: kaniko-secret
                        secret: 
                          secretName: acr-secret
                          items:
                            - key: .dockerconfigjson
                              path: config.json
                '''
                defaultContainer 'maven'
            }
        }
        environment {
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
            stage('Push with Kaniko') {
                steps {
                    container('kaniko') {
                        sh '''
                        echo "Running kaniko cmd"
                        /kaniko/executor -f `pwd`/Dockerfile -c `pwd` --destination="${TAVROS_REG_HOST}/${NAME}:${VERSION}"
                        '''
                    }
                }
            }
        }
    }
}
