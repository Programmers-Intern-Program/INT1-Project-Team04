terraform {
  required_providers {
    oci = {
      source  = "oracle/oci"
      version = "~> 6.0"
    }
  }
}

data "oci_core_images" "ubuntu" {
  compartment_id           = var.compartment_ocid
  operating_system         = "Canonical Ubuntu"
  operating_system_version = "22.04"
  shape                    = var.instance_shape
  sort_by                  = "TIMECREATED"
  sort_order               = "DESC"
}

# ── VCN ──────────────────────────────────────────────────────────────────────
resource "oci_core_vcn" "monitoring" {
  compartment_id = var.compartment_ocid
  cidr_blocks    = ["10.1.0.0/16"]
  display_name   = "${var.project_name}-vcn"
  dns_label      = "monitoringvcn"
}

resource "oci_core_internet_gateway" "monitoring" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.monitoring.id
  display_name   = "${var.project_name}-igw"
  enabled        = true
}

resource "oci_core_route_table" "monitoring" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.monitoring.id
  display_name   = "${var.project_name}-rt"

  route_rules {
    destination       = "0.0.0.0/0"
    destination_type  = "CIDR_BLOCK"
    network_entity_id = oci_core_internet_gateway.monitoring.id
  }
}

# ── 보안 규칙 ─────────────────────────────────────────────────────────────────
resource "oci_core_security_list" "monitoring" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.monitoring.id
  display_name   = "${var.project_name}-sl"

  ingress_security_rules {
    description = "SSH"
    protocol    = "6"
    source      = var.admin_cidr
    tcp_options {
      min = 22
      max = 22
    }
  }

  ingress_security_rules {
    description = "Prometheus UI"
    protocol    = "6"
    source      = var.admin_cidr
    tcp_options {
      min = 9090
      max = 9090
    }
  }

  ingress_security_rules {
    description = "Grafana UI"
    protocol    = "6"
    source      = var.admin_cidr
    tcp_options {
      min = 3000
      max = 3000
    }
  }

  egress_security_rules {
    destination = "0.0.0.0/0"
    protocol    = "all"
  }
}

resource "oci_core_subnet" "monitoring" {
  compartment_id    = var.compartment_ocid
  vcn_id            = oci_core_vcn.monitoring.id
  cidr_block        = "10.1.1.0/24"
  display_name      = "${var.project_name}-subnet"
  dns_label         = "monitoring"
  route_table_id    = oci_core_route_table.monitoring.id
  security_list_ids = [oci_core_security_list.monitoring.id]
}

# ── 인스턴스 ─────────────────────────────────────────────────────────────────
resource "oci_core_instance" "monitoring" {
  compartment_id      = var.compartment_ocid
  availability_domain = var.availability_domain
  display_name        = "${var.project_name}-app"
  shape               = var.instance_shape

  source_details {
    source_type             = "image"
    source_id               = data.oci_core_images.ubuntu.images[0].id
    boot_volume_size_in_gbs = var.boot_volume_size_gb
  }

  create_vnic_details {
    subnet_id        = oci_core_subnet.monitoring.id
    assign_public_ip = true
    display_name     = "${var.project_name}-vnic"
  }

  metadata = {
    ssh_authorized_keys = var.ssh_public_key
    user_data = base64encode(templatefile("${path.module}/user_data.sh.tpl", {
      project_dir = var.project_dir
    }))
  }

  freeform_tags = {
    Project = var.project_name
  }
}

# ── 모니터링 서버 파일 업로드 + 서비스 기동 ───────────────────────────────────────
resource "null_resource" "deploy_monitoring" {
  depends_on = [oci_core_instance.monitoring]

  triggers = {
    instance_id         = oci_core_instance.monitoring.id
    docker_compose_hash = filemd5("${path.root}/../../docker/monitor/docker-compose.yml")
  }

  connection {
    type        = "ssh"
    host        = oci_core_instance.monitoring.public_ip
    user        = "ubuntu"
    private_key = file(pathexpand(var.ssh_private_key_path))
  }

  provisioner "remote-exec" {
    inline = ["cloud-init status --wait"]
  }

  provisioner "file" {
    source      = "${path.root}/../../docker/"
    destination = "${var.project_dir}/docker"
  }

  provisioner "file" {
    content = join("\n", [
      "NODE_A_IP=${var.app_server_ip}",
      "NODE_B_IP=${oci_core_instance.monitoring.public_ip}",
      "GRAFANA_USER=admin",
      "GRAFANA_PASSWORD=${var.grafana_password}",
      "GRAFANA_DOMAIN=${var.grafana_domain}",
      "",
    ])
    destination = "${var.project_dir}/docker/monitor/.env"
  }

  provisioner "remote-exec" {
    inline = [
      "sudo apt-get install -y iptables-persistent 2>/dev/null || true",
      "for port in 22 9090 3000; do sudo iptables -C INPUT -m state --state NEW -p tcp --dport $port -j ACCEPT 2>/dev/null || sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport $port -j ACCEPT; done",
      "sudo netfilter-persistent save 2>/dev/null || true",

      "if [ ! -f /swapfile ]; then sudo fallocate -l 1G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile && echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab; fi",

      "sudo docker network create monitor-net 2>/dev/null || true",

      # NODE_A_IP 치환 (prometheus.yml, grafana datasources.yml)
      "sed -i 's|$${NODE_A_IP}|${var.app_server_ip}|g' ${var.project_dir}/docker/config/prometheus/prometheus.yml",
      "sed -i 's|$${NODE_A_IP}|${var.app_server_ip}|g' ${var.project_dir}/docker/config/grafana/provisioning/datasources/datasources.yml",

      "cd ${var.project_dir}/docker/monitor && sudo docker compose --env-file .env up -d",
      "echo '✅ 모니터링 서비스 기동 완료'"
    ]
  }
}
