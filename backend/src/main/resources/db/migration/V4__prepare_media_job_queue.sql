-- 큐 컬럼 추가 전 QUEUED 작업은 실제 대기 시각을 알 수 없다. 0은 시각 미상 표시값이다.
UPDATE media_jobs SET queued_at = 0 WHERE status = 'QUEUED' AND queued_at IS NULL;
UPDATE media_jobs SET available_at = queued_at WHERE status = 'QUEUED' AND available_at IS NULL;

CREATE INDEX idx_media_jobs_queue ON media_jobs (status, queued_at, id);
