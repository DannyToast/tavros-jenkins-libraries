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
                      - name: jnlp
                        image: jenkins/inbound-agent:3391.va_37fa_a_305d6d-2-jdk21

                      - name: git
                        image: atlassian/default-image:4.20230726
                        command:
                        - sleep
                        args:
                        - infinity

                      - name: maven
                        image: maven:4.0.0-rc-5-eclipse-temurin-21
                        securityContext:
                          runAsUser: 1000
                        command:
                        - /bin/sh
                        - -c
                        args:
                        - tail -f /dev/null

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
                    script: 'mvn help:evaluate -Dexpression=project.version -q -DforceStdout | tail -n 1'
            ).trim()}"""

            NAME = """${sh(
                    returnStdout: true,
                    script: 'mvn help:evaluate -Dexpression=project.artifactId -q -DforceStdout | tail -n 1'
            ).trim()}"""
        }

        stages {
            stage('Test/Build') {
                steps {
                    script {
                        utils.shResource "maven-verify.sh"
                    }
                }
            }

            stage('Debug Image Name') {
                steps {
                    echo "NAME=[${NAME}]"
                    echo "VERSION=[${VERSION}]"
                    echo "IMAGE=[${TAVROS_REG_HOST}/${NAME}:${VERSION}]"
                }
            }

            stage('Push with Kaniko') {
                steps {
                    container('kaniko') {
                        sh '''
                        echo "Running kaniko cmd"
                        echo "Destination=${TAVROS_REG_HOST}/${NAME}:${VERSION}"

                        /kaniko/executor \
                          -f `pwd`/Dockerfile \
                          -c `pwd` \
                          --destination="${TAVROS_REG_HOST}/${NAME}:${VERSION}"
                        '''
                    }
                }
            }
        }
    }
}
