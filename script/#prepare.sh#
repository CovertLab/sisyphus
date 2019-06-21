#!/usr/bin/env bash

apt update
apt install -y openjdk-11-jdk

adduser sisyphus
su -l sisyphus

mkdir bin
curl "https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein" > bin/lein
chmod +x bin/lein
export PATH=$HOME/bin:$PATH


