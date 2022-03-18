#!/usr/bin/env bash

if [[ $# -lt 1 ]]; then
    echo "Usage: $0 PRESTO_VERSION" >&2
    exit 1
fi

set -euo pipefail

# Retrieve the script directory.
SCRIPT_DIR="${BASH_SOURCE%/*}"
cd ${SCRIPT_DIR}

PRESTO_VERSION=$1
SERVER_LOCATION="https://repo1.maven.org/maven2/com/facebook/presto/presto-server/${PRESTO_VERSION}/presto-server-${PRESTO_VERSION}.tar.gz"
CLIENT_LOCATION="https://repo1.maven.org/maven2/com/facebook/presto/presto-cli/${PRESTO_VERSION}/presto-cli-${PRESTO_VERSION}-executable.jar"

WORK_DIR="$(mktemp -d)"
curl -o ${WORK_DIR}/presto-server-${PRESTO_VERSION}.tar.gz ${SERVER_LOCATION}
tar -C ${WORK_DIR} -xzf ${WORK_DIR}/presto-server-${PRESTO_VERSION}.tar.gz
rm ${WORK_DIR}/presto-server-${PRESTO_VERSION}.tar.gz
cp -R bin ${WORK_DIR}/presto-server-${PRESTO_VERSION}
cp -R default ${WORK_DIR}/

curl -o ${WORK_DIR}/presto-cli-${PRESTO_VERSION}-executable.jar ${CLIENT_LOCATION}
chmod +x ${WORK_DIR}/presto-cli-${PRESTO_VERSION}-executable.jar

CONTAINER="jasonchrion/presto:${PRESTO_VERSION}"

docker build ${WORK_DIR} --pull --platform linux/amd64 -f Dockerfile -t ${CONTAINER}-amd64 --build-arg "PRESTO_VERSION=${PRESTO_VERSION}"
docker build ${WORK_DIR} --pull --platform linux/arm64 -f Dockerfile -t ${CONTAINER}-arm64 --build-arg "PRESTO_VERSION=${PRESTO_VERSION}"

rm -r ${WORK_DIR}

# Source common testing functions
. container-test.sh

test_container ${CONTAINER}-amd64 linux/amd64
test_container ${CONTAINER}-arm64 linux/arm64

docker image inspect -f '🚀 Built {{.RepoTags}} {{.Id}}' ${CONTAINER}-amd64
docker image inspect -f '🚀 Built {{.RepoTags}} {{.Id}}' ${CONTAINER}-arm64
