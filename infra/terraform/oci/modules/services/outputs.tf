output "next_steps" {
  value = <<-EOT
    [매번] main 브랜치 push → GitHub Actions Blue/Green 배포
    [규칙 변경] extra_ingress_ports 수정 후 terraform apply
  EOT
}
