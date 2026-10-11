-- NULL inherits the service-wide default (THROTTL_FAIL_CLOSED).
ALTER TABLE policies ADD COLUMN failure_mode VARCHAR(32);
