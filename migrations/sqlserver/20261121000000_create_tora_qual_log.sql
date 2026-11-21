--
--    Copyright 2010-2016 the original author or authors.
--
--    Licensed under the Apache License, Version 2.0 (the "License");
--    you may not use this file except in compliance with the License.
--    You may obtain a copy of the License at
--
--       http://www.apache.org/licenses/LICENSE-2.0
--
--    Unless required by applicable law or agreed to in writing, software
--    distributed under the License is distributed on an "AS IS" BASIS,
--    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
--    See the License for the specific language governing permissions and
--    limitations under the License.
--

-- // create_tora_qual_log
-- Migration SQL that makes the change goes here.

-- tora-qual-lock 资格变更日志（SQL Server 2012+）。表名改了的话，同时配置 tora.qual.log.table
CREATE TABLE tora_qual_log (
  id BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
  quota_name NVARCHAR(100) NOT NULL,
  suffix NVARCHAR(100) NOT NULL DEFAULT '',
  log_type VARCHAR(10) NOT NULL,
  log_field VARCHAR(10) NOT NULL DEFAULT '',
  amount BIGINT NOT NULL DEFAULT 0,
  total_before BIGINT NOT NULL DEFAULT 0,
  total_after BIGINT NOT NULL DEFAULT 0,
  used_before BIGINT NOT NULL DEFAULT 0,
  used_after BIGINT NOT NULL DEFAULT 0,
  expire_before DATETIME2(3) NULL,
  expire_after DATETIME2(3) NULL,
  biz_id NVARCHAR(64) NOT NULL DEFAULT '',
  remark NVARCHAR(255) NOT NULL DEFAULT '',
  created_at DATETIME2(3) NOT NULL
);
CREATE INDEX idx_tora_qual_log_key ON tora_qual_log (quota_name, suffix, created_at);
CREATE INDEX idx_tora_qual_log_biz ON tora_qual_log (biz_id);
CREATE INDEX idx_tora_qual_log_time ON tora_qual_log (created_at);

-- //@UNDO
-- SQL to undo the change goes here.

DROP TABLE tora_qual_log;
