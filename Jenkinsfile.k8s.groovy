#!groovy

//String podTemplateConcat = "${serviceName}-${buildNumber}-${uuid}"
def label = "worker-${UUID.randomUUID().toString()}"
println("label")
println("${label}")

podTemplate(
        label: "${label}",
        containers: [
                containerTemplate(name: 'jnlp', image: 'jenkins/jnlp-slave:alpine'),
                containerTemplate(name: 'docker', image: 'docker', command: 'cat', ttyEnabled: true,
                        resourceRequestCpu: '500m',
                        resourceLimitCpu: '800m',
                        resourceRequestMemory: '1024Mi',
                        resourceLimitMemory: '2048Mi'),
                containerTemplate(name: 'kubectl', image: 'lachlanevenson/k8s-kubectl:v1.8.8', command: 'cat', ttyEnabled: true),
                containerTemplate(name: 'helm', image: 'lachlanevenson/k8s-helm:latest', command: 'cat', ttyEnabled: true),
                containerTemplate(name: 'httpie', image: 'blacktop/httpie', command: 'cat', ttyEnabled: true)
        ],
        imagePullSecrets: ["regcred"],
        volumes: [
                hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock'),
                secretVolume(mountPath: '/etc/.dockercreds', secretName: 'docker-creds')
        ]
) {

    node("${label}") {

        def srvRepo = "quay.io/reportportal/service-api"

        def k8sDir = "kubernetes"
        def ciDir = "reportportal-ci"
        def appDir = "app"
        def k8sNs = "reportportal"

        parallel 'Checkout Infra': {
            stage('Checkout Infra') {
                sh 'mkdir -p ~/.ssh'
                sh 'ssh-keyscan -t rsa github.com >> ~/.ssh/known_hosts'
                sh 'ssh-keyscan -t rsa git.epam.com >> ~/.ssh/known_hosts'
                dir(k8sDir) {
                    git branch: "master", url: 'https://github.com/reportportal/kubernetes.git'

                }
                dir(ciDir) {
                    git credentialsId: 'epm-gitlab-key', branch: "master", url: 'git@git.epam.com:epmc-tst/reportportal-ci.git'
                }

            }
        }, 'Checkout Service': {
            stage('Checkout Service') {
                dir(appDir) {
                    def br = "${params.COMMIT_HASH}".isEmpty()?:'develop'
                    git branch: br, url: 'https://github.com/reportportal/service-api.git'
                }
            }
        }

        def test = load "${ciDir}/jenkins/scripts/test.groovy"
        def utils = load "${ciDir}/jenkins/scripts/util.groovy"
        def helm = load "${ciDir}/jenkins/scripts/helm.groovy"
        def docker = load "${ciDir}/jenkins/scripts/docker.groovy"

        docker.init()
        helm.init()
        utils.scheduleRepoPoll()

//        dir('app') {
//            try {
//                container('docker') {
//                    stage('Build App') {
//                        sh "gradle build --full-stacktrace"
//                    }
//                }
//            } finally {
//                junit 'build/test-results/**/*.xml'
//                dependencyCheckPublisher pattern: 'build/reports/dependency-check-report.xml'
//
//            }
//
//        }
        def snapshotVersion = utils.readProperty("app/gradle.properties", "version")
        def buildVersion = "BUILD-${env.BUILD_NUMBER}"
        def srvVersion = "${snapshotVersion}-${buildVersion}"
        def tag = "$srvRepo:$srvVersion"


        stage('Build Docker Image') {
            dir(appDir) {
                container('docker') {
                    sh "docker build -f docker/Dockerfile-develop --build-arg buildNumber=$buildVersion -t $tag ."
                    sh "docker push $tag"
                }
            }


        }
        stage('Deploy to Dev Environment') {
            container('helm') {
                dir("$k8sDir/reportportal/v5") {
                    sh 'helm dependency update'
                }
                sh "helm upgrade --reuse-values --set serviceapi.repository=$srvRepo --set serviceapi.tag=$srvVersion --wait -f ./$ciDir/rp/values-ci.yml reportportal ./$k8sDir/reportportal/v5"
            }
        }

        stage('Execute DVT Tests') {
            def srvUrl
            container('kubectl') {
                def srvName = utils.getServiceName(k8sNs, "api")
                srvUrl = utils.getServiceEndpoint(k8sNs, srvName)
            }
            if (srvUrl == null) {
                error("Unable to retrieve service URL")
            }
            container('httpie') {
                test.checkVersion("http://$srvUrl", "$srvVersion")
            }
        }

    }
}

