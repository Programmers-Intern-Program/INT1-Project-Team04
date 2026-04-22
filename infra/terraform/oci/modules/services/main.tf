terraform {
  required_providers {
    oci = {
      source  = "oracle/oci"
      version = "~> 6.0"
    }
  }
}

# ── 보안 규칙 ────────────────────────────────────────────────────────────────
locals {
  base_ingress_rules = [
    { description = "SSH",                   protocol = "6", source = var.admin_cidr,               sourceType = "CIDR_BLOCK", isStateless = false, tcpOptions = { destinationPortRange = { min = 22,   max = 22   } } },
    { description = "HTTP",                  protocol = "6", source = "0.0.0.0/0",                  sourceType = "CIDR_BLOCK", isStateless = false, tcpOptions = { destinationPortRange = { min = 80,   max = 80   } } },
    { description = "HTTPS",                 protocol = "6", source = "0.0.0.0/0",                  sourceType = "CIDR_BLOCK", isStateless = false, tcpOptions = { destinationPortRange = { min = 443,  max = 443  } } },
    { description = "NPM UI",                protocol = "6", source = var.admin_cidr,               sourceType = "CIDR_BLOCK", isStateless = false, tcpOptions = { destinationPortRange = { min = 81,   max = 81   } } },
    { description = "Node Exporter",         protocol = "6", source = "${var.monitoring_ip}/32",    sourceType = "CIDR_BLOCK", isStateless = false, tcpOptions = { destinationPortRange = { min = 9100, max = 9100 } } },
    { description = "PG Exporter",           protocol = "6", source = "${var.monitoring_ip}/32",    sourceType = "CIDR_BLOCK", isStateless = false, tcpOptions = { destinationPortRange = { min = 9187, max = 9187 } } },
    { description = "Spring Boot Actuator",  protocol = "6", source = "${var.monitoring_ip}/32",    sourceType = "CIDR_BLOCK", isStateless = false, tcpOptions = { destinationPortRange = { min = 9091, max = 9091 } } },
    { description = "Loki",                  protocol = "6", source = "${var.monitoring_ip}/32",    sourceType = "CIDR_BLOCK", isStateless = false, tcpOptions = { destinationPortRange = { min = 3100, max = 3100 } } },
  ]
  extra_ingress_rules = [for p in var.extra_ingress_ports : {
    description = "extra port ${p}", protocol = "6", source = "0.0.0.0/0", sourceType = "CIDR_BLOCK", isStateless = false, tcpOptions = { destinationPortRange = { min = p, max = p } }
  }]
  egress_rules = [
    { destination = "0.0.0.0/0", destinationType = "CIDR_BLOCK", protocol = "all", isStateless = false }
  ]
  all_ingress_rules = jsonencode(concat(local.base_ingress_rules, local.extra_ingress_rules))
  all_egress_rules  = jsonencode(local.egress_rules)
}

resource "null_resource" "security_rules" {
  triggers = {
    security_list_id = var.default_security_list_ocid
    ingress_rules    = local.all_ingress_rules
    egress_rules     = local.all_egress_rules
  }

  provisioner "local-exec" {
    interpreter = ["bash", "-c"]
    command = <<-EOT
      oci network security-list update \
        --security-list-id "${var.default_security_list_ocid}" \
        --ingress-security-rules '${local.all_ingress_rules}' \
        --egress-security-rules '${local.all_egress_rules}' \
        --force
    EOT
  }
}

# ── Step 1: 서버 준비 (Docker, 네트워크, 방화벽, Swap) ────────────────────────
resource "null_resource" "prepare" {
  triggers = {
    repo_url = var.repo_url
  }

  connection {
    type        = "ssh"
    host        = var.server_ip
    user        = "ubuntu"
    private_key = file(pathexpand(var.ssh_private_key_path))
  }

  provisioner "remote-exec" {
    inline = [
      "sudo mkdir -p ${var.project_dir}/infra/docker/prod",
      "sudo chown -R ubuntu:ubuntu ${var.project_dir}",

      "if ! command -v docker &>/dev/null; then curl -fsSL https://get.docker.com | sudo sh && sudo usermod -aG docker ubuntu && echo '✅ Docker 설치 완료'; fi",

      "sudo apt-get install -y iptables-persistent 2>/dev/null || true",
      "for port in 22 80 443 81 9100 9187 9091 3100 ${join(" ", var.extra_ingress_ports)}; do sudo iptables -C INPUT -m state --state NEW -p tcp --dport $port -j ACCEPT 2>/dev/null || sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport $port -j ACCEPT; done",
      "sudo netfilter-persistent save 2>/dev/null || true",

      "sudo docker network create prod-net 2>/dev/null || true",

      "if [ ! -f /swapfile ]; then sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile && echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab && echo '✅ Swap 2G 설정 완료'; fi",
    ]
  }
}

# ── Step 2: 파일 업로드 + .env 생성 ────────────────────────────────────────
resource "null_resource" "upload_files" {
  depends_on = [null_resource.prepare]

  triggers = {
    docker_compose_hash = filemd5("${path.root}/../../docker/prod/docker-compose.yml")
  }

  connection {
    type        = "ssh"
    host        = var.server_ip
    user        = "ubuntu"
    private_key = file(pathexpand(var.ssh_private_key_path))
  }

  provisioner "file" {
    source      = "${path.root}/../../docker/"
    destination = "${var.project_dir}/infra/docker"
  }

  provisioner "file" {
    content = join("\n", [
      "NODE_A_IP=${var.server_ip}",
      "GITHUB_ORG=${var.github_org}",
      "TAG=latest",
      "PG_USER=${var.pg_user}",
      "PG_PASSWORD=${var.pg_password}",
      "REDIS_PASSWORD=${var.redis_password}",
      "LANGFUSE_NEXTAUTH_SECRET=${var.langfuse_nextauth_secret}",
      "LANGFUSE_SALT=${var.langfuse_salt}",
      "LANGFUSE_SECRET_KEY=${var.langfuse_secret_key}",
      "LANGFUSE_PUBLIC_KEY=${var.langfuse_public_key}",
      "LANGFUSE_DOMAIN=${var.langfuse_domain}",
      "",
    ])
    destination = "${var.project_dir}/infra/docker/prod/.env"
  }
}

# ── Step 3: 인프라 서비스 기동 ────────────────────────────────────────────────
resource "null_resource" "deploy" {
  depends_on = [null_resource.upload_files]

  triggers = {
    repo_url        = var.repo_url
    server_ip       = var.server_ip
    ssh_private_key = file(pathexpand(var.ssh_private_key_path))
    project_dir     = var.project_dir
  }

  provisioner "remote-exec" {
    connection {
      type        = "ssh"
      host        = var.server_ip
      user        = "ubuntu"
      private_key = file(pathexpand(var.ssh_private_key_path))
    }
    inline = [
      "sudo docker rm -f $(sudo docker ps -aq) 2>/dev/null || true",
      "sudo docker volume prune -f",
      "sudo docker image prune -af",
      "sudo docker network create prod-net 2>/dev/null || true",
      # mcp-server는 CI/CD 첫 배포 후 이미지 생성 — 나머지 인프라 서비스만 기동
      "cd ${var.project_dir} && sudo docker compose -f infra/docker/prod/docker-compose.yml --env-file infra/docker/prod/.env up -d postgres redis langfuse-web langfuse-worker nginx-proxy-manager loki promtail node-exporter postgres-exporter",
      "echo '✅ 주 서버 인프라 서비스 기동 완료'"
    ]
  }

  provisioner "remote-exec" {
    when = destroy
    connection {
      type        = "ssh"
      host        = self.triggers.server_ip
      user        = "ubuntu"
      private_key = self.triggers.ssh_private_key
    }
    inline = [
      "sudo docker compose -f ${self.triggers.project_dir}/infra/docker/prod/docker-compose.yml down 2>/dev/null || true",
      "sudo docker volume prune -f",
      "echo '✅ 주 서버 서비스 중단 완료'"
    ]
  }
}

# ── Step 4: NPM proxy host 초기 설정 ─────────────────────────────────────────
resource "null_resource" "npm_setup" {
  depends_on = [null_resource.deploy]

  triggers = {
    repo_url   = var.repo_url
    fe_domain  = var.fe_domain
    be_domain  = var.be_domain
  }

  connection {
    type        = "ssh"
    host        = var.server_ip
    user        = "ubuntu"
    private_key = file(pathexpand(var.ssh_private_key_path))
  }

  provisioner "remote-exec" {
    inline = [
      "echo '⏳ NPM 기동 대기...'",
      "for i in $(seq 1 24); do curl -sf http://localhost:81/api/ >/dev/null 2>&1 && break || sleep 5; done",

      "TOKEN=$(curl -sf -X POST http://localhost:81/api/tokens -H 'Content-Type: application/json' -d '{\"identity\":\"${var.npm_email}\",\"secret\":\"${var.npm_password}\"}' | jq -r .token)",
      "[[ -z \"$TOKEN\" || \"$TOKEN\" == \"null\" ]] && { echo '❌ NPM 로그인 실패'; exit 1; }",

      # BE proxy host (초기: be_a → CI/CD 첫 배포 후 be_a/be_b 전환)
      "BE_ID=$(curl -sf -H \"Authorization: Bearer $TOKEN\" http://localhost:81/api/nginx/proxy-hosts | jq -r '.[] | select(.domain_names[] == \"${var.be_domain}\") | .id')",
      "BE_BODY=$(jq -n --arg d '${var.be_domain}' --arg h 'be_a' '{domain_names:[$d],forward_scheme:\"http\",forward_host:$h,forward_port:8080,access_list_id:0,certificate_id:0,ssl_forced:false,caching_enabled:false,block_exploits:true,allow_websocket_upgrade:true,advanced_config:\"\",locations:[],meta:{}}')",
      "if [ -z \"$BE_ID\" ]; then curl -sf -o /dev/null -X POST -H \"Authorization: Bearer $TOKEN\" -H 'Content-Type: application/json' -d \"$BE_BODY\" http://localhost:81/api/nginx/proxy-hosts; else curl -sf -o /dev/null -X PUT -H \"Authorization: Bearer $TOKEN\" -H 'Content-Type: application/json' -d \"$BE_BODY\" http://localhost:81/api/nginx/proxy-hosts/$BE_ID; fi",

      # FE proxy host (초기: fe_a)
      "FE_ID=$(curl -sf -H \"Authorization: Bearer $TOKEN\" http://localhost:81/api/nginx/proxy-hosts | jq -r '.[] | select(.domain_names[] == \"${var.fe_domain}\") | .id')",
      "FE_BODY=$(jq -n --arg d '${var.fe_domain}' --arg h 'fe_a' '{domain_names:[$d],forward_scheme:\"http\",forward_host:$h,forward_port:3000,access_list_id:0,certificate_id:0,ssl_forced:false,caching_enabled:false,block_exploits:true,allow_websocket_upgrade:true,advanced_config:\"\",locations:[],meta:{}}')",
      "if [ -z \"$FE_ID\" ]; then curl -sf -o /dev/null -X POST -H \"Authorization: Bearer $TOKEN\" -H 'Content-Type: application/json' -d \"$FE_BODY\" http://localhost:81/api/nginx/proxy-hosts; else curl -sf -o /dev/null -X PUT -H \"Authorization: Bearer $TOKEN\" -H 'Content-Type: application/json' -d \"$FE_BODY\" http://localhost:81/api/nginx/proxy-hosts/$FE_ID; fi",

      # Grafana proxy host (모니터링 서버 IP:3000 으로 포워딩)
      "GF_ID=$(curl -sf -H \"Authorization: Bearer $TOKEN\" http://localhost:81/api/nginx/proxy-hosts | jq -r '.[] | select(.domain_names[] == \"${var.grafana_domain}\") | .id')",
      "GF_BODY=$(jq -n --arg d '${var.grafana_domain}' --arg h '${var.monitoring_ip}' '{domain_names:[$d],forward_scheme:\"http\",forward_host:$h,forward_port:3000,access_list_id:0,certificate_id:0,ssl_forced:false,caching_enabled:false,block_exploits:true,allow_websocket_upgrade:false,advanced_config:\"\",locations:[],meta:{}}')",
      "if [ -z \"$GF_ID\" ]; then curl -sf -o /dev/null -X POST -H \"Authorization: Bearer $TOKEN\" -H 'Content-Type: application/json' -d \"$GF_BODY\" http://localhost:81/api/nginx/proxy-hosts; else curl -sf -o /dev/null -X PUT -H \"Authorization: Bearer $TOKEN\" -H 'Content-Type: application/json' -d \"$GF_BODY\" http://localhost:81/api/nginx/proxy-hosts/$GF_ID; fi",

      "echo '✅ NPM proxy host 설정 완료 (SSL은 NPM UI에서 최초 1회 설정)'"
    ]
  }
}

# ── Step 5: 인스턴스 Rebuild (destroy 시만 실행) ──────────────────────────────
resource "null_resource" "instance_rebuild" {
  depends_on = [null_resource.npm_setup]

  triggers = {
    instance_ocid    = var.instance_ocid
    compartment_ocid = var.compartment_ocid
  }

  provisioner "local-exec" {
    when        = destroy
    interpreter = ["bash", "-c"]
    command     = <<-EOT
      set -e
      trap '
        echo "❌ Rebuild 실패 — 인스턴스 강제 기동 시도..."
        oci compute instance action --instance-id "${self.triggers.instance_ocid}" --action START 2>/dev/null || true
      ' ERR
      echo "🔄 주 서버 인스턴스 Rebuild 시작..."

      INSTANCE_INFO=$(oci compute instance get \
        --instance-id "${self.triggers.instance_ocid}")
      SOURCE_IMAGE_ID=$(echo "$INSTANCE_INFO" | python3 -c "import sys,json; d=json.load(sys.stdin)['data']; print(d.get('source-details',{}).get('image-id',''))")
      AVAILABILITY_DOMAIN=$(echo "$INSTANCE_INFO" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['availability-domain'])")

      if [ -z "$SOURCE_IMAGE_ID" ] || [ "$SOURCE_IMAGE_ID" == "null" ]; then
        echo "❌ 소스 이미지 ID 조회 실패. Rebuild 중단."
        exit 1
      fi

      oci compute instance action \
        --instance-id "${self.triggers.instance_ocid}" \
        --action STOP \
        --wait-for-state STOPPED

      OLD_BOOT_VOLUME_ID=$(oci compute boot-volume-attachment list \
        --instance-id "${self.triggers.instance_ocid}" \
        --compartment-id "${self.triggers.compartment_ocid}" \
        --availability-domain "$AVAILABILITY_DOMAIN" \
        --query 'data[0]."boot-volume-id"' \
        --raw-output)

      oci compute instance update-instance-update-instance-source-via-image-details \
        --instance-id "${self.triggers.instance_ocid}" \
        --source-details-image-id "$SOURCE_IMAGE_ID" \
        --force \
        --wait-for-state STOPPED

      for i in $(seq 1 20); do
        STATE=$(oci compute instance get \
          --instance-id "${self.triggers.instance_ocid}" \
          --query 'data."lifecycle-state"' --raw-output 2>/dev/null || echo "UNKNOWN")
        if [ "$STATE" = "STOPPED" ]; then
          oci compute instance action \
            --instance-id "${self.triggers.instance_ocid}" \
            --action START \
            --wait-for-state RUNNING && break
        fi
        sleep 15
      done

      sleep 30
      if [ -n "$OLD_BOOT_VOLUME_ID" ] && [ "$OLD_BOOT_VOLUME_ID" != "null" ]; then
        oci bv boot-volume delete \
          --boot-volume-id "$OLD_BOOT_VOLUME_ID" \
          --force
      fi

      echo "✅ 주 서버 인스턴스 Rebuild 완료"
    EOT
  }
}
