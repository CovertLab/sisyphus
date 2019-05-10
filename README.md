# sisyphus

![SISYPHUS](https://github.com/CovertLab/sisyphus/blob/master/resources/public/sisyphus.png)

"Forever, I shall be a stranger to myself."

― Albert Camus

## concept

Sisyphus is a task execution system that focuses on decentralization, running commands in containers, and pulling and pushing inputs and outputs to and from a data store. There is no leader so you can add new nodes on the fly to help out with load, and scale them back down again when you are done.

## setup

In order to use cloud storage and GCR you have to authorize with google. This means running `gcloud auth login` at some point with an account that is tied to google cloud, and you have to follow the directions [here](https://cloud.google.com/storage/docs/reference/libraries) for "setting up authentication" to access the cloud resources programmatically. 

## storage

There are three distinct levels of paths that need to be distinguished:

* remote - anything that lives in the object store.
* local - anything on the system that is running a Sisyphus node.
* internal - anything inside the docker container.

Sisyphus pulls inputs down from the object store to the local system, then maps a volume from that local path into a specified path internal to the container that is actually going to run the code. Once it is complete, a reverse mapping takes outputs from internal paths and maps them to local paths, which are then uploaded back into the remote object store. This is the full round trip of a single task being executed and has the ultimate effect of operating on objects in an object store and adding the results back as new objects into that same store.

In general, clients of Sisyphus only really need to be aware of remote and internal paths. Local paths are managed entirely by Sisyphus, but it is good to know that this intermediate layer exists. 

## task documents

Each task is represented by a task document. This document details the command that will be run, the image it will be run with, any inputs the command needs to run, and any outputs we want to record later. Here is an example document:

```js
{"image": "alpine:latest",
 "inputs": {
   "sisyphus:data/clear": "/mnt/clear"},
 "outputs": {
   "sisyphus:data/crypt": "/mnt/crypt"},
 "commands": [{
   "command": [
     "md5sum",
     "/mnt/clear"],
   "stdout": "/mnt/crypt"}]}
```

There are four keys:

* `image` - what docker image to use for this command.
* `inputs` - map of `bucket:remote/path` to `/internal/container/path`, for copying down files from the object store and knowing where to bind them inside the container.
* `outputs` - same as inputs, but opposite, so a map of object store paths `bucket:remote/path` where any files at the original internal path '/internal/container/path' will be uploaded to once execution is complete.
* `commands` - a list of commands, each one with a `command` key (an array of command tokens) and keys for `stdout`, `stdin` and `stderr` with internal paths, if required.

## triggering execution

Any running Sisyphus nodes will be listening on a rabbitmq channel and will consume any available task message when they are ready to execute something.

## running

To start a new Sisyphus node that is ready to accept new tasks:

    $ lein run

Once this is up, you can put tasks on the queue and the new node will accept them and begin executing. In a new terminal execute the following commands:

    $ lein repl
    > (def rabbit (rabbit/connect! {}))
    > (def task (task/load-task "test/tasks/md5sum.json")
    > (rabbit/publish! rabbit task)

You will see the worker node pick up this task and begin executing.

∞
