(locally)

install gcloud

> sudo snap install google-cloud-sdk --classic

login through google cloud

> gcloud auth login

set the project

> gcloud config set project allen-discovery-center-mcovert

allocate new machine

> gcloud compute --project=allen-discovery-center-mcovert instances create NAME --zone=us-west1-b --machine-type=n1-standard-1 --subnet=default --network-tier=PREMIUM --maintenance-policy=MIGRATE --service-account=441871726775-compute@developer.gserviceaccount.com --scopes=https://www.googleapis.com/auth/devstorage.read_only,https://www.googleapis.com/auth/logging.write,https://www.googleapis.com/auth/monitoring.write,https://www.googleapis.com/auth/servicecontrol,https://www.googleapis.com/auth/service.management.readonly,https://www.googleapis.com/auth/trace.append --image=ubuntu-1804-bionic-v20190617 --image-project=ubuntu-os-cloud --boot-disk-size=10GB --boot-disk-type=pd-standard

create a key for the service account and download it, then
scp it to the gateway

> gcloud compute scp $GCLOUD_KEY gateway:.cloud.json

ssh into the new machine

> gcloud compute ssh gateway

export the value

> export GOOGLE_APPLICATION_CREDENTIALS=$HOME/.cloud.json

activate service acccount

> gcloud auth activate-service-account sisyphus@allen-discovery-center-mcovert.iam.gserviceaccount.com --key-file ~/.cloud.json

install pip and ipython

> sudo apt update
> sudo apt install python-pip ipython

clone gaia

> git clone https://github.com/prismofeverything/gaia.git

cd into the gaia client

> cd gaia/client/python

run the requirements.txt

> pip install -r requirements.txt

start ipython

> ipython

load gaia client

> import gaia
> config = {
>     'gaia_host': '10.138.0.21:24442',
>     'kafka_host': '10.138.0.2:9092'}
> flow = gaia.Gaia(config)