-- changes to old schema
-- First, clear all foreign key constraints and update primary keys.

ALTER TABLE UpdateSpec
  DROP FOREIGN KEY fk_update_specs_vehicle
;

ALTER TABLE RequiredPackage
  DROP FOREIGN KEY fk_downloads_update_specs
;

ALTER TABLE InstallHistory
  DROP FOREIGN KEY install_history_vin_fk
;

-- Then, update primary keys.

ALTER TABLE Vehicle
  RENAME TO Device
, DROP PRIMARY KEY
, CHANGE vin uuid CHAR(36) NOT NULL -- regular UUID
, ADD PRIMARY KEY (namespace, uuid)
;

-- At last, re-wire all foreign key constraints

ALTER TABLE UpdateSpec
  CHANGE vin device_uuid CHAR(36) NOT NULL
, ADD FOREIGN KEY fk_update_specs_device (namespace, device_uuid) REFERENCES Device(namespace, uuid)
;

ALTER TABLE RequiredPackage
  CHANGE vin device_uuid CHAR(36) NOT NULL
, ADD FOREIGN KEY fk_downloads_update_specs (update_request_id, device_uuid) REFERENCES UpdateSpec(update_request_id, device_uuid)
;

ALTER TABLE InstallHistory
  CHANGE vin device_uuid CHAR(36) NOT NULL
, ADD FOREIGN KEY install_history_device_fk (namespace, device_uuid) REFERENCES Device(namespace, uuid)
;

-- additions to schema

ALTER TABLE Device
  ADD device_id VARCHAR (200) NOT NULL UNIQUE -- subsumes VINs and other unspecified schemes
, ADD device_type SMALLINT NOT NULL
;

CREATE TABLE DeviceType (
  id SMALLINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(200) NULL
);

-- populate device types

INSERT INTO DeviceType (name) VALUES ("Other");
INSERT INTO DeviceType (name) VALUES ("Vehicle");
