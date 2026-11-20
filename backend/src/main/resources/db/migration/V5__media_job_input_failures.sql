ALTER TABLE media_jobs ADD COLUMN last_error_code VARCHAR(64) NULL;
ALTER TABLE media_jobs ADD COLUMN last_failure_lease_id VARCHAR(36) NULL;
