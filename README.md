# sisyphus

"Forever, I shall be a stranger to myself."
― Albert Camus, The Myth of Sisyphus

## concept

Sisyphus is a task execution system that focuses on decentralization (there is no leader so you can add new nodes to help out with load on the fly), running commands in specified docker containers and pulling and pushing inputs and outputs to and from a data store.

## task documents

Each task is represented by a task document. This document details the container, command, inputs and outputs the task will field. Here is an example document:

```js
{"container": "alpine:latest",
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

As you can see, there are four keys:

* `container` - what docker container to use for this command.
* `inputs` - map of `bucket:path` to `/internal/container/path`, for copying down files from the object store and knowing where to put them inside the container.
* `outputs` - same as inputs, but opposite, so a map of object store paths `bucket:path` to the internal path where the command placed various files in the container that we want to retain.
* `commands` - a list of commands, each one with a `command` key (an array of command tokens) and keys for `stdout`, `stdin` and `stderr` with local paths, if required. 