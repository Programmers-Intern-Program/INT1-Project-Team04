variable "compartment_ocid" { type = string }

variable "availability_domain" { type = string }

variable "project_name" {
  type    = string
  default = "int1-team04-monitoring"
}

variable "project_dir" {
  type    = string
  default = "/home/ubuntu/monitoring"
}

variable "instance_shape" {
  type    = string
  default = "VM.Standard.E2.1.Micro"
}

variable "boot_volume_size_gb" {
  type    = number
  default = 150
}

variable "ssh_public_key" { type = string }

variable "ssh_private_key_path" {
  description = "모니터링 서버 SSH 접속용 개인키 경로"
  type        = string
  default     = "~/.ssh/id_rsa"
}

variable "admin_cidr" {
  type    = string
  default = "0.0.0.0/0"
}

variable "app_server_ip" {
  description = "주 서버 퍼블릭 IP (Prometheus scrape 대상)"
  type        = string
}

variable "grafana_password" {
  type      = string
  sensitive = true
}

variable "grafana_domain" {
  type = string
}
