[![Test Status](https://github.com/viperproject/viperserver/actions/workflows/scala.yml/badge.svg?branch=master)](https://github.com/viperproject/viperserver/actions/workflows/scala.yml?query=branch%3Amaster)
[![License: MPL 2.0](https://img.shields.io/badge/License-MPL%202.0-brightgreen.svg)](./LICENSE)

This is ViperServer, an HTTP server that manages verification requests to different tools from the Viper tool stack.

The main two Viper tools (a.k.a verification backends) currently are: 

- [Carbon](https://github.com/viperproject/carbon), a verification condition generation (VCG) backend for the Viper language.
- [Silicon](https://github.com/viperproject/silicon), a symbolic execution verification backend.


### The Purpose of ViperServer ###

1. Viper IDE: integration of Viper into Visual Studio Code (VS Code). Viper IDE provides the best user experience for Viper.
   More details here: http://viper.ethz.ch/downloads/
1. Facilitate the development of verification IDEs for Viper frontends, such as: 
    - [Gobra](https://github.com/viperproject/gobra), the Viper-based verifier for the Go language
    - [Prusti](https://github.com/viperproject/prusti-dev/), the Viper-based verifier for the Rust language
1. Avoid 1-3 second delays caused by JVM startup time. ViperServer offers a robust alternative to, e.g.,
   [Nailgun](https://github.com/facebook/nailgun).
1. Develop Viper encodings more efficiently with caching.
1. Interact with Viper tools programmatically using the HTTP API. A reference client implementation (in Python) is
   available via [viper_client](https://github.com/viperproject/viper_client).

For more details about using Viper, please visit: http://viper.ethz.ch/downloads/


### Installation Instructions ###

* Clone [silicon](https://github.com/viperproject/silicon/) and [carbon](https://github.com/viperproject/carbon/) repositories in your computer, in separate directories.
* Execute `git submodule init; git submodule update` in both, the silicon and carbon, directories to fetch their depending `silver` repository. Even though silicon's silver repository is actually used for compilation of ViperServer, we assume that they reference the same silver commit.
* Clone **viperserver** (this repository) in your computer, in another directory.
* From within the directory where you installed viperserver, create a symbolic links to the directories where you installed silicon and carbon.
* On Linux/Mac OS X:  
``` 
ln -s <relative path to diretory where you installed silicon> silicon
ln -s <relative path to diretory where you installed carbon> carbon
```
* On Windows:  
```
mklink /D silicon <relative path to diretory where you installed silicon>
mklink /D carbon <relative path to diretory where you installed carbon>
```
* Compile by typing: ```sbt compile```

* Other supported SBT commands are: ```sbt stage``` (produces fine-grained jar files), ```sbt assembly``` (produces a single fat jar file).

### Running Tests ###

* Set the environment variable ```Z3_EXE``` to an executable of a recent version of [Z3](https://github.com/Z3Prover/z3).

* Run the following command: ```sbt test```.


### Who do I talk to? ###

* This repository is maintained by [Linard Arquint](mailto:linard.arquint@inf.ethz.ch).
