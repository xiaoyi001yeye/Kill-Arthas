ALTER TABLE access_target
  ADD COLUMN ssh_port INTEGER;

UPDATE access_target
SET ssh_port = 22
WHERE ssh_port IS NULL;

ALTER TABLE access_target
  ALTER COLUMN ssh_port SET NOT NULL;
