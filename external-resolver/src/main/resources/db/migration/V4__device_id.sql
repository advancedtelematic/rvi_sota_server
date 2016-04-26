-- changes to old schema
-- First, clear all foreign key constraints and update primary keys.

ALTER TABLE InstalledPackage
  DROP FOREIGN KEY installed_package_vin_fk
;

ALTER TABLE InstalledComponent
  DROP FOREIGN KEY installed_component_vin_fk
;

ALTER TABLE Firmware
  DROP FOREIGN KEY fk_firmware_vehicle
;

-- Then, update primary keys.

ALTER TABLE InstalledPackage
  DROP PRIMARY KEY
, CHANGE vin device_id VARCHAR(200) NOT NULL
, ADD PRIMARY KEY (namespace, device_id, packageName, packageVersion)
;

ALTER TABLE InstalledComponent
  DROP PRIMARY KEY
, CHANGE vin device_id CHAR(200) NOT NULL
, ADD PRIMARY KEY (namespace, device_id, partNumber)
;

ALTER TABLE Firmware
  DROP PRIMARY KEY
, CHANGE vin device_id CHAR(200) NOT NULL
, ADD PRIMARY KEY (namespace, module, firmware_id, device_id)
;

ALTER TABLE Vehicle
  RENAME TO Device
, DROP PRIMARY KEY
, CHANGE vin id VARCHAR(200) NOT NULL -- provider specific device Ids (VIN, ...)
, ADD PRIMARY KEY (namespace, id)
;

-- At last, re-wire all foreign key constraints

ALTER TABLE InstalledPackage
  ADD FOREIGN KEY installed_package_device_fk (namespace, device_id) REFERENCES Device(namespace, id)
;

ALTER TABLE InstalledComponent
  ADD FOREIGN KEY installed_component_device_fk (namespace, device_id) REFERENCES Device(namespace, id)
;

ALTER TABLE Firmware
  ADD FOREIGN KEY fk_firmware_device (namespace, device_id) REFERENCES Device(namespace, id)
;
