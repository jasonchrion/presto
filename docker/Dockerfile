#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
FROM registry.access.redhat.com/ubi8/ubi

ENV JAVA_HOME /usr/lib/jvm/zulu11
RUN \
    set -xeu && \
    yum -y -q install https://cdn.azul.com/zulu/bin/zulu-repo-1.0.0-1.noarch.rpm && \
    yum -y -q install python3 zulu11-jdk less && \
    yum -q clean all && \
    rm -rf /var/cache/yum && \
    alternatives --set python /usr/bin/python3 && \
    groupadd presto --gid 1000 && \
    useradd presto --uid 1000 --gid 1000 && \
    mkdir -p /usr/lib/presto /data/presto && \
    chown -R "presto:presto" /usr/lib/presto /data/presto

ARG PRESTO_VERSION
COPY presto-cli-${PRESTO_VERSION}-executable.jar /usr/bin/presto
COPY --chown=presto:presto presto-server-${PRESTO_VERSION} /usr/lib/presto
COPY --chown=presto:presto default/etc /etc/presto

EXPOSE 8080
USER presto:presto
ENV LANG en_US.UTF-8
CMD ["/usr/lib/presto/bin/run-presto"]
HEALTHCHECK --interval=10s --timeout=5s --start-period=10s \
  CMD /usr/lib/presto/bin/health-check
