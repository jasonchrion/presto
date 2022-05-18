#!/usr/bin/env bash

set -euo pipefail

SOURCE_DIR=".."

# Retrieve the script directory.
SCRIPT_DIR="${BASH_SOURCE%/*}"
cd ${SCRIPT_DIR}

# Move to the root directory to run maven for current version.
pushd ${SOURCE_DIR}
PRESTO_VERSION=$(./mvnw --quiet help:evaluate -Dexpression=project.version -DforceStdout)
popd

WORK_DIR="$(mktemp -d)"
cp ${SOURCE_DIR}/presto-server/target/presto-server-${PRESTO_VERSION}.tar.gz ${WORK_DIR}
tar -C ${WORK_DIR} -xzf ${WORK_DIR}/presto-server-${PRESTO_VERSION}.tar.gz
rm ${WORK_DIR}/presto-server-${PRESTO_VERSION}.tar.gz
cp -R bin ${WORK_DIR}/presto-server-${PRESTO_VERSION}
cp -R default ${WORK_DIR}/

cp ${SOURCE_DIR}/presto-cli/target/presto-cli-${PRESTO_VERSION}-executable.jar ${WORK_DIR}

CONTAINER="prestpdb/presto:${PRESTO_VERSION}"

docker build ${WORK_DIR} --pull --platform linux/amd64 -f Dockerfile -t ${CONTAINER}-amd64 --build-arg "PRESTO_VERSION=${PRESTO_VERSION}"
docker build ${WORK_DIR} --pull --platform linux/arm64 -f Dockerfile -t ${CONTAINER}-arm64 --build-arg "PRESTO_VERSION=${PRESTO_VERSION}"

rm -r ${WORK_DIR}

# Source common testing functions
. container-test.sh

test_container ${CONTAINER}-amd64 linux/amd64
test_container ${CONTAINER}-arm64 linux/arm64

docker image inspect -f '🚀 Built {{.RepoTags}} {{.Id}}' ${CONTAINER}-amd64
docker image inspect -f '🚀 Built {{.RepoTags}} {{.Id}}' ${CONTAINER}-arm64
