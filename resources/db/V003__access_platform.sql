ALTER TABLE access_requests ADD COLUMN revision BIGINT NOT NULL DEFAULT 1;
ALTER TABLE access_requests ADD COLUMN route_level VARCHAR(32) NOT NULL DEFAULT 'DIVISION';
ALTER TABLE access_requests ADD COLUMN decided_at VARCHAR(40);
ALTER TABLE access_requests ADD COLUMN created_user_id VARCHAR(80);
CREATE TABLE access_pending_numbers (auth_number VARCHAR(80) PRIMARY KEY, request_id VARCHAR(80) NOT NULL REFERENCES access_requests(id));
INSERT INTO access_pending_numbers SELECT auth_number,id FROM access_requests WHERE state IN ('PENDING','ESCALATED');
CREATE TABLE access_notification_links (event_id VARCHAR(80) PRIMARY KEY REFERENCES notification_events(id), request_id VARCHAR(80) NOT NULL REFERENCES access_requests(id));
CREATE INDEX idx_access_scope ON access_requests(organization_id,state,created_at);
CREATE UNIQUE INDEX idx_ack_session ON security_acknowledgements(user_id,session_id,notice_version);
