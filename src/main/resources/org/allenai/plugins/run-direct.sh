#!/bin/bash

# Script to start a single server with the given main class.
# This assumes that we're working out of a universal install (lib/ directory present, and this
# script is in bin/).
#
# This takes one argument: the fully-qualified classname to run. Any additional arguments will be
# passed on to the underlying JVM.
#
# You can specify custom JVM arguments by setting the environment variable JVM_ARGS.

# Set default memory settings JVM_ARGS if undefined.
if [ -z "${JVM_ARGS}" ]; then
  JVM_ARGS="-Xms512m -Xmx512m"
fi

SCRIPT_NAME=`basename $0`
# Use a compact usage if we're being invoked as another script.
if [[ $SCRIPT_NAME == run-class.sh ]]; then
  USAGE="usage: $SCRIPT_NAME mainClass [<args>]"
else
  USAGE="usage: $SCRIPT_NAME [<args>]"
fi

if [[ $# < 1 ]]; then
  echo "$USAGE"
  exit 1
fi
MAIN_CLASS=$1
shift

# Change to the root of the install.
SCRIPT_DIR=`dirname $0`
cd "$SCRIPT_DIR/.."

# Configure JVM logging.
LOGBACK_CONF=("-Dlogback.appname=$SHORT_NAME")
if [ -e conf/logback.xml ]; then
  LOGBACK_CONF+=("-Dlogback.configurationFile=conf/logback.xml")
fi
CONF_FILE="-Dconfig.file=conf/application.conf"
# Use a per-env config, if it exists.
if [ -e conf/env.conf ]; then
  CONF_FILE="-Dconfig.file=conf/env.conf"
fi

# Add a cache-key config, user tests for existence.
ADD_CACHE_KEY=""
if [ -e conf/cacheKey.Sha1 ]; then
  CACHEKEY=$(<conf/cacheKey.Sha1)
  ADD_CACHE_KEY="-Dapplication.cacheKey=$CACHEKEY"
fi

CLASSPATH=`find lib -name '*.jar' | tr "\\n" :`
JAVA_CMD=(java $JVM_ARGS -classpath $CLASSPATH $CONF_FILE
  ${LOGBACK_CONF[@]} $ADD_CACHE_KEY)

# Run java.
echo "running in `pwd` ..."
echo "${JAVA_CMD[@]} ${MAIN_CLASS} $@"
${JAVA_CMD[@]} "$MAIN_CLASS" "$@"
