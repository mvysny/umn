#!/bin/bash
set -e -o pipefail
CP=`ls lib/*.jar|tr '\n' ':'`
java $JAVA_OPTS -cp $CP com.gitlab.mvysny.umn.proxy.MainKt "$@"
