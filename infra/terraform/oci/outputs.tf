output "monitoring_public_ip" {
  value = module.monitoring.public_ip
}

output "grafana_url" {
  value = module.monitoring.grafana_url
}

output "prometheus_url" {
  value = module.monitoring.prometheus_url
}

output "next_steps" {
  value = <<-EOT
    1. GitHub Actions 실행: main 브랜치 push → Blue/Green 앱 배포
    2. NPM UI: http://${var.app_server_ip}:81 에서 도메인·SSL 확인
    3. Grafana: ${module.monitoring.grafana_url} (초기 계정: admin / <grafana_password>)
  EOT
}
