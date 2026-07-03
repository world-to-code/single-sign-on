-- Retire the legacy group-manager mechanism: scope now comes entirely from resource_role (managers were
-- migrated to resource ADMIN grants by GroupManagerConverter in the prior release). Contract phase — run
-- only after that converter release has migrated any existing manager rows.
DROP TABLE IF EXISTS user_group_manager;
