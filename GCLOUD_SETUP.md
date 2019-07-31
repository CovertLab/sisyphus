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

   The following lines set a consistent Python 2.7 version for running `gcloud`.
   Without this, `gcloud` can fail to start up, e.g. when the `pyenv version` is
   set to python 3, displaying an error message such as
   `pyenv: python2: command not found`.

   ```sh
   # Set the Python version for Cloud SDK. It has to be Python 2.7.
   export CLOUDSDK_PYTHON=$(pyenv shell 2.7.16; pyenv which python)
   ```

   (That assumes you installed Python 2.7.16 via the command `pyenv install 2.7.16`.)

   Test if the installer put `gcloud` on your shell path one way or another by
   opening a new shell and running `which gcloud` (or `where` on Windows). If
   it doesn't find gcloud, then add lines like the following example to your shell
   profile:

   ```sh
   # Update PATH for the Google Cloud SDK and gcloud CLI.
   if [ -f '$HOME/dev/google-cloud-sdk/path.bash.inc' ]; then . '$HOME/dev/google-cloud-sdk/path.bash.inc'; fi
   
   # The next line enables shell command completion for gcloud.
   if [ -f '$HOME/dev/google-cloud-sdk/completion.bash.inc' ]; then . '$HOME/dev/google-cloud-sdk/completion.bash.inc'; fi
   ```

   Then open a new shell or restart your shell:

   `exec -l $SHELL`

3. run [gcloud init](https://cloud.google.com/sdk/gcloud/reference/init)

   * When it asks for a default project, enter `allen-discovery-center-mcovert`
   * When it asks for a default Compute Engine zone, enter `us-west1-b`

   ```
   gcloud init
   ```

   This will:
   * initialize `gcloud` and its SDK tools
   * run some diagnostics
   * log in using your user account credentials
   * set configuration defaults

   (To change settings later, you can re-run `gcloud init` or run specific commands
   like `gcloud auth login`, `gcloud config set zone us-west1-b`, and
   `gcloud config set project allen-discovery-center-mcovert`.)

   (There's good documentation available via `gcloud help` and the
   [gcloud web reference](https://cloud.google.com/sdk/gcloud/reference/))

4. set up docker

   1. Create a Docker ID [on their website](https://www.docker.com/).
   2. Install [Docker Desktop](https://www.docker.com/products/docker-desktop).
   3. Log in to your Docker ID from the Docker client.
   4. Set up [shell completion for Docker](https://docs.docker.com/docker-for-mac/).
   5. You can exit Docker Desktop when you're not using it.
