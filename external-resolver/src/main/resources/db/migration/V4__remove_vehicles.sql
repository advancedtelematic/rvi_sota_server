ALTER TABLE InstalledPackage
DROP FOREIGN KEY installed_package_vin_fk;

ALTER TABLE InstalledComponent
DROP FOREIGN KEY installed_component_vin_fk;

ALTER TABLE Firmware
DROP FOREIGN KEY fk_firmware_vehicle;

DROP TABLE Vehicle;
