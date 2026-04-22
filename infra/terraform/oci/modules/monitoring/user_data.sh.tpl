#!/bin/bash
set -euo pipefail
exec > >(tee /var/log/user_data.log | logger -t user_data) 2>&1

echo "=== [1/3] Docker 설치 ==="
apt-get update -y
apt-get install -y ca-certificates curl gnupg git

install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
chmod a+r /etc/apt/keyrings/docker.gpg

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
  https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" \
  > /etc/apt/sources.list.d/docker.list

apt-get update -y
apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin

systemctl enable docker
systemctl start docker
usermod -aG docker ubuntu

echo "=== [2/3] 네트워크 및 디렉터리 생성 ==="
docker network create monitor-net || true
mkdir -p ${project_dir}
chown ubuntu:ubuntu ${project_dir}

echo "=== [3/3] 완료 (파일 업로드 및 서비스 기동은 Terraform이 처리) ==="
