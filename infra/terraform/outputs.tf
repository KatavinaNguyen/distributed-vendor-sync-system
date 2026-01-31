output "ingest_index_table_name" {
  description = "DynamoDB table used for idempotency enforcement"
  value       = aws_dynamodb_table.dvss_ingest_index.name
}
