@Library('jenkins-helpers') _

def label = "metrics-plugin-${UUID.randomUUID().toString()}"

podTemplate(
    label: label,
    containers: [containerTemplate(name: 'maven',
                    image: 'maven:3.6.3-jdk-11',
                    command: '/bin/cat -',
                    resourceRequestCpu: '1000m',
                    resourceRequestMemory: '2000Mi',
                    resourceLimitCpu: '1000m',
                    resourceLimitMemory: '2000Mi',
                    ttyEnabled: true),
    ]
) {
    properties([buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '20'))])
    node(label) {
        container('maven') {
            stage("checking out code") {
                checkout(scm)
            }
            stage("test and build java package") {
                sh 'mvn -B -Djenkins.version=2.222 test || true'
                junit(allowEmptyResults: false, testResults: '**/target/surefire-reports/*.xml')
                summarizeTestResults()
                sh 'mvn -B -Djenkins.version=2.222 -DskipTests verify'
            }
        }
    }
}