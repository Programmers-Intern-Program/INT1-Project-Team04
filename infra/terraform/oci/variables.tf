# ── OCI 인증 ────────────────────────────────────────────────────────────────
variable "tenancy_ocid" { type = string }
variable "user_ocid" { type = string }
variable "fingerprint" { type = string }
variable "private_key_path" {
  type    = string
  default = "~/.oci/oci_api_key.pem"
}
variable "region" {
  type    = string
  default = "us-phoenix-1"
}
variable "compartment_ocid" { type = string }

# ── 공통 ─────────────────────────────────────────────────────────────────────
variable "repo_url" {
  type    = string
  default = "https://github.com/Programmers-Intern-Program/INT1-Project-Team04.git"
}

variable "ssh_public_key" {
  description = "인스턴스 등록용 SSH 공개키 내용"
  type        = string
}

variable "ssh_private_key_path" {
  description = "SSH 개인키 경로 (주 서버 + 모니터링 서버 공통)"
  type        = string
  default     = "~/.ssh/id_rsa"
}

variable "admin_cidr" {
  description = "SSH·관리 UI 접근 허용 CIDR (본인 IP/32 권장)"
  type        = string
  default     = "0.0.0.0/0"
}

# ── 주 서버 ──────────────────────────────────────────────────────────────────
variable "app_server_ip" {
  description = "주 서버 퍼블릭 IP (고정)"
  type        = string
}

variable "app_server_vcn_ocid" {
  description = "주 서버 VCN OCID"
  type        = string
}

variable "app_server_default_security_list_ocid" {
  description = "주 서버 Default Security List OCID"
  type        = string
}

variable "app_server_instance_ocid" {
  description = "주 서버 인스턴스 OCID (Rebuild용)"
  type        = string
}

variable "app_server_project_dir" {
  type    = string
  default = "/home/ubuntu/app"
}

variable "extra_ingress_ports" {
  description = "서비스별 추가 개방 포트"
  type        = list(number)
  default     = []
}

# ── GitHub / 이미지 ───────────────────────────────────────────────────────────
variable "github_org" {
  description = "GitHub 조직명 (GHCR 이미지 경로용)"
  type        = string
}

# ── 애플리케이션 도메인 ────────────────────────────────────────────────────────
variable "fe_domain" { type = string }
variable "be_domain" { type = string }
variable "grafana_domain" { type = string }
variable "langfuse_domain" { type = string }

# ── Nginx Proxy Manager ───────────────────────────────────────────────────────
variable "npm_email" { type = string }
variable "npm_password" {
  type      = string
  sensitive = true
}

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
variable "langfuse_encryption_key" {
  type      = string
  sensitive = true
}

# ── Grafana ───────────────────────────────────────────────────────────────────
variable "grafana_password" {
  type      = string
  sensitive = true
}

# ── 모니터링 서버 ─────────────────────────────────────────────────────────────
variable "monitoring_availability_domain" {
  description = "모니터링 인스턴스 가용 도메인"
  type        = string
}
