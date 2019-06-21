#!/usr/bin/env bash

apt update
apt install -y openjdk-11-jdk

adduser sisyphus
su -l sisyphus

mkdir bin
curl "https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein" > bin/lein
chmod +x bin/lein
export PATH=$HOME/bin:$PATH

git clone https://github.com/CovertLab/sisyphus.git

gcloud auth activate-service-account sisyphus@allen-discovery-center-mcovert.iam.gserviceaccount.com --key-file ~/.cloud.json
gcloud auth configure-docker
cat .cloud.json | docker login -u _json_key --password-stdin https://gcr.io
