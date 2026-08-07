-- AS-1: process path master table
CREATE TABLE process_path (
  process_path_id VARCHAR(64)  NOT NULL,
  content         JSON         NOT NULL,
  description     VARCHAR(512) NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (process_path_id)
);
