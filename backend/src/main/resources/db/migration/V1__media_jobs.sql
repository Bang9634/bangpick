CREATE TABLE media_jobs (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL,
    video_type VARCHAR(30) NOT NULL,
    video_bytes BIGINT NOT NULL,
    input_par_id VARCHAR(4096),
    upload_expires BIGINT NOT NULL,
    lease_id VARCHAR(36),
    lease_expires BIGINT,
    result_par_id VARCHAR(4096),
    result_key VARCHAR(160),
    result_bytes BIGINT
);
