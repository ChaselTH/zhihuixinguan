ALTER TABLE submission_items ADD COLUMN workflow_state VARCHAR(32) NOT NULL DEFAULT 'LEGACY_PUBLISHED';
CREATE TABLE workflow_record_state (record_id VARCHAR(80) PRIMARY KEY REFERENCES official_records(id), submission_id VARCHAR(80) REFERENCES submissions(id), owner_id VARCHAR(80), stage VARCHAR(32) NOT NULL, reason CLOB NOT NULL DEFAULT '', updated_at VARCHAR(40) NOT NULL);
CREATE INDEX idx_workflow_record_stage ON workflow_record_state(stage,owner_id,updated_at);
CREATE TABLE workflow_item_events (id VARCHAR(80) PRIMARY KEY, submission_id VARCHAR(80) REFERENCES submissions(id), record_id VARCHAR(80) NOT NULL REFERENCES official_records(id), stage VARCHAR(32) NOT NULL, action VARCHAR(40) NOT NULL, actor_id VARCHAR(80) NOT NULL, actor_name VARCHAR(100) NOT NULL, actor_role VARCHAR(32) NOT NULL, request_id VARCHAR(100) NOT NULL, reason CLOB NOT NULL, event_at VARCHAR(40) NOT NULL);
CREATE INDEX idx_workflow_item_events ON workflow_item_events(record_id,event_at,id);
INSERT INTO workflow_record_state(record_id,submission_id,owner_id,stage,reason,updated_at) SELECT id,NULL,NULL,'LEGACY_PUBLISHED','',updated_at FROM official_records;
UPDATE submission_items SET workflow_state=(SELECT CASE state WHEN 'SUBMITTED' THEN 'BRANCH_REVIEW' WHEN 'RETURNED' THEN 'RETURNED' ELSE 'LEGACY_PUBLISHED' END FROM submissions WHERE submissions.id=submission_items.submission_id);
