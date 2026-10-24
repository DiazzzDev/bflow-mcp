terraform {
  required_version = ">= 1.9.0, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.12, < 7.0" # aws_cognito_managed_login_branding exists since 6.12
    }
  }
}

provider "aws" {
  region = var.aws_region
}
