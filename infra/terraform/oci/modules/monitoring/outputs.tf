output "public_ip" {
  description = "모니터링 서버 퍼블릭 IP"
  value       = oci_core_instance.monitoring.public_ip
}

output "grafana_url" {
  value = "http://${oci_core_instance.monitoring.public_ip}:3000"
}

output "prometheus_url" {
  value = "http://${oci_core_instance.monitoring.public_ip}:9090"
}

output "ssh_command" {
  value = "ssh ubuntu@${oci_core_instance.monitoring.public_ip}"
}
