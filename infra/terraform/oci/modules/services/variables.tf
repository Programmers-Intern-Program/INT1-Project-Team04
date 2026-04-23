variable "server_ip" { type = string }

variable "ssh_private_key_path" {
  type    = string
  default = "~/.ssh/id_rsa"
}

variable "repo_url" { type = string }

variable "project_dir" {
  type    = string
  default = "/home/ubuntu/app"
}

variable "instance_ocid" {
  description = "인스턴스 OCID (Rebuild용)"
  type        = string
}

variable "compartment_ocid" { type = string }
variable "vcn_ocid" { type = string }

variable "default_security_list_ocid" {
  description = "Default Security List OCID"
  type        = string
}

variable "admin_cidr" {
  type    = string
  default = "0.0.0.0/0"
}

variable "extra_ingress_ports" {
  type    = list(number)
  default = []
}

variable "monitoring_ip" {
  description = "모니터링 서버 퍼블릭 IP"
  type        = string
}

# ── Nginx Proxy Manager ───────────────────────────────────────────────────────
variable "npm_email" { type = string }
variable "npm_password" {
  type      = string
  sensitive = true
}

variable "fe_domain" { type = string }
variable "be_domain" { type = string }
variable "grafana_domain" { type = string }

# ── GitHub / 이미지 ───────────────────────────────────────────────────────────
variable "github_org" { type = string }

# ── 데이터베이스 ──────────────────────────────────────────────────────────────
variable "pg_user" {
  type    = string
  default = "appuser"
}
variable "pg_password" {
  type      = string
  sensitive = true
}

# ── Redis ─────────────────────────────────────────────────────────────────────
variable "redis_password" {
  type      = string
  sensitive = true
}

# ── Langfuse ─────────────────────────────────────────────────────────────────
variable "langfuse_nextauth_secret" {
  type      = string
  sensitive = true
}
variable "langfuse_salt" {
  type      = string
  sensitive = true
}
variable "langfuse_secret_key" {
  type      = string
  sensitive = true
}
variable "langfuse_public_key" {
  type      = string
  sensitive = true
}
variable "langfuse_domain" { type = string }
variable "langfuse_encryption_key" {
  type      = string
  sensitive = true
}
