CREATE TABLE `MultiTargetUpdates` (
    `id` char(36) NOT NULL PRIMARY KEY,
    `deviceIdentifier` char(200) NOT NULL,
    `targetUpdates` char(200) NOT NULL,
    `targetHash` char(200) NOT NULL,
    `targetSize` long NOT NULL
);

