ALTER TABLE `Campaign`
DROP COLUMN `delta_from_name`,
MODIFY COLUMN `delta_from_version` varchar(64);

