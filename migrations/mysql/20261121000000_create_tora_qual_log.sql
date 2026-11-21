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

-- tora-qual-lock 资格变更日志（MySQL 5.7+）。表名改了的话，同时配置 tora.qual.log.table
CREATE TABLE tora_qual_log (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  quota_name VARCHAR(100) NOT NULL COMMENT '资格名',
  suffix VARCHAR(100) NOT NULL DEFAULT '' COMMENT '后缀（用户 id 等），空字符串表示共用资格',
  log_type VARCHAR(10) NOT NULL COMMENT 'CREATE 创建 / UPDATE 更新 / DELETE 删除',
  log_field VARCHAR(10) NOT NULL DEFAULT '' COMMENT '更新了什么：TOTAL 总资格 / AVAILABLE 可用资格 / EXPIRE 有效期',
  amount BIGINT NOT NULL DEFAULT 0 COMMENT '变化量：扣减为负、退回为正',
  total_before BIGINT NOT NULL DEFAULT 0 COMMENT '变更前总资格',
  total_after BIGINT NOT NULL DEFAULT 0 COMMENT '变更后总资格',
  used_before BIGINT NOT NULL DEFAULT 0 COMMENT '变更前已用',
  used_after BIGINT NOT NULL DEFAULT 0 COMMENT '变更后已用',
  expire_before DATETIME(3) NULL COMMENT '变更前到期时间，NULL 表示永不过期或原来不存在',
  expire_after DATETIME(3) NULL COMMENT '变更后到期时间，NULL 表示永不过期或已删除',
  biz_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '业务单号',
  remark VARCHAR(255) NOT NULL DEFAULT '' COMMENT '备注',
  created_at DATETIME(3) NOT NULL COMMENT '记录时间',
  PRIMARY KEY (id),
  KEY idx_tora_qual_log_key (quota_name, suffix, created_at),
  KEY idx_tora_qual_log_biz (biz_id),
  KEY idx_tora_qual_log_time (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='tora-qual-lock 资格变更日志';

-- //@UNDO
-- SQL to undo the change goes here.

DROP TABLE tora_qual_log;
