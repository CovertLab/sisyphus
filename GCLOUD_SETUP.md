# Install and log in to the Google Cloud SDK

1. install the Google Cloud SDK and the `gcloud` command line tools

   See [Installing Google Cloud SDK — Installation options](https://cloud.google.com/sdk/install#installation_options):

   * Debian/Ubuntu: [install with apt-get](https://cloud.google.com/sdk/docs/downloads-apt-get)
   * Red Hat Enterprise Linux 7/CentOS 7: [install with yum](https://cloud.google.com/sdk/docs/downloads-yum)
   * Snap for Linux:  
      `sudo snap install google-cloud-sdk --classic`
   * Other Linux: [install with curl](https://cloud.google.com/sdk/docs/downloads-interactive#linux)
   * maOS: [install with curl](https://cloud.google.com/sdk/docs/downloads-interactive#mac)
   * Windows: [install with the Cloud SDK installer](https://cloud.google.com/sdk/docs/downloads-interactive#windows)

2. add these lines to your shell `.profile` or `.bash_profile` as needed

   The following lines set a Python 2.7 version for running `gcloud`. As written, it
   assumes you used `pyenv` to install Python, e.g. `pyenv install 2.7.16`:

   ```sh
   # Set the Python version for Cloud SDK. It has to be Python 2.7.
   export CLOUDSDK_PYTHON=$(pyenv shell 2.7.16; pyenv which python)
   ```

   The following lines put the gcloud tools on your shell path, and vary depending on
   where you installed gcloud. The installer should add these lines for you:

   ```sh
   # Update PATH for the Google Cloud SDK and gcloud CLI.
   if [ -f '$HOME/dev/google-cloud-sdk/path.bash.inc' ]; then . '$HOME/dev/google-cloud-sdk/path.bash.inc'; fi
   
   # The next line enables shell command completion for gcloud.
   if [ -f '$HOME/dev/google-cloud-sdk/completion.bash.inc' ]; then . '$HOME/dev/google-cloud-sdk/completion.bash.inc'; fi
   ```

2. log in to gcloud

   ```
   gcloud auth login
   ```

3. set the project

   ```
   gcloud config set project allen-discovery-center-mcovert
   ```

4. set up docker

   1. Create a Docker ID [on their website](https://www.docker.com/).
   2. Install [Docker Desktop](https://www.docker.com/products/docker-desktop).
   3. Log in to your Docker ID from the Docker client.
   4. Set up [shell completion for Docker](https://docs.docker.com/docker-for-mac/).
   5. You can exit Docker Desktop when you're not using it.
