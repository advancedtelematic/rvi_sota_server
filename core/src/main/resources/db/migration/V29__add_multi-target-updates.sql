CREATE TABLE `MultiTargetUpdates` (
    `id` char(36) NOT NULL PRIMARY KEY,
    `device_identifier` char(200) NOT NULL,
    `target_updates` char(200) NOT NULL,
    `target_hash` char(128) NOT NULL,
    `hash_method` char(20) NOT NULL,
    `target_size` long NOT NULL
);

