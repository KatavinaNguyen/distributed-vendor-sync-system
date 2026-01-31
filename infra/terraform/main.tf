terraform {
  required_version = ">= 1.4.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

resource "aws_dynamodb_table" "dvss_ingest_index" {
  name         = "${var.name_prefix}_ingest_index"
  billing_mode = "PAY_PER_REQUEST"

  hash_key  = "vendorId"
  range_key = "externalEventId"

  attribute {
    name = "vendorId"
    type = "S"
  }

  attribute {
    name = "externalEventId"
    type = "S"
  }

  # Optional TTL for dedupe window cleanup
  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  # Strong durability signal
  point_in_time_recovery {
    enabled = true
  }

  tags = {
    Project = "distributed-vendor-sync-system"
    Service = "dvss"
  }
}
