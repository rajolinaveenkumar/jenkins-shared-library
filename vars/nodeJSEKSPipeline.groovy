//  i have desined this library to work for both nodejs(backend) and nginx(frontend)

def call(Map configmap) {
    pipeline {
        agent {
            label 'configmap.get('label')'
        }

        
        environment {
            poroject = configmap.get('project')
            env_name = configmap.get('env')
            component = configmap.get('component')
            app_version = ''
            acc_id = configmap.get('acc_id')
            region = configmap.get('region')
        }

        parameters {
            booleanParam(name: 'BuildImage', defaultValue: false, description: 'Please check the box to build the image')

            booleanParam(name: 'deploy', defaultValue: false, description: 'Select to deploy or not')

        }

        options {
            timeout(time: 40, unit: 'MINUTES')
            ansiColor('xtrem')
            disableConcurrentBuilds()        
        }

        stages {

            stage('Read app version') {
                steps {
                    script {
                        def packageJson = readJSON file: 'scripts/package.json'
                        app_version = packageJson.version
                        echo "app Version: ${app_version}"
                    }

                }
            }

            stage('SonarQube') {
                environment {
                    SCANNER_HOME = tool 'sonar-7.0'
                }
                steps {
                    withSonarQubeEnv('sonar-7.0') {
                        sh "${SCANNER_HOME}/bin/sonar-scanner"
                    }
                }
            }

            stage('Quality Gate') {
                steps {
                    timeout(time: 6, unit: 'MINUTES') {
                        waitForQualityGate abortPipeline: true
                    }
                }
            }

            stage('Build image') {
                when {
                    expression {
                        params.BuildImage
                    }
                }
                steps {
                    withAWS(region: 'us-east-1', credentials: 'aws-auth') {
                        sh """
                            aws ecr get-login-password --region ${region} | docker login --username AWS --password-stdin ${acc_id}.dkr.ecr.${region}.amazonaws.com
                            
                            docker build -t ${acc_id}.dkr.ecr.${region}.amazonaws.com/expense-dev/${component}:${app_version} .

                            docker images

                            docker push ${acc_id}.dkr.ecr.${region}.amazonaws.com/expense-dev/${component}:${app_version}
                        """
                    }
                }
            }

            stage('Trigger the Deploy') {
                when {
                    expression {
                        params.deploy
                    }
                }
                steps {
                    build job: "${component}-cd", parameters: [string(name: "image_version", value: "${app_version}"), string(name: "env_name", value: "dev")], wait: false
                }
            }

        }

        post {
            always{
                echo 'this will run always'
                deleteDir()
            }
            success{
                echo 'this will run on success'
            }
            failure{
                echo 'this will run at failure'
            }
        }

    }

}