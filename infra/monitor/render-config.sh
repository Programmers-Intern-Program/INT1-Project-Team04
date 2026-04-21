#!/bin/bash
# Node B 배포 전 prometheus.yml의 NODE_A_IP 치환
set -e

source .env

sed "s/\${NODE_A_IP}/${NODE_A_IP}/g" \
  ../config/prometheus/prometheus.yml > /tmp/prometheus-rendered.yml

cp /tmp/prometheus-rendered.yml ../config/prometheus/prometheus.yml
echo "prometheus.yml rendered with NODE_A_IP=${NODE_A_IP}"
