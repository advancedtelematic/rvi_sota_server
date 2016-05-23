ALTER TABLE UpdateSpec
DROP FOREIGN KEY fk_update_specs_vehicle;

ALTER TABLE InstallHistory
DROP FOREIGN KEY install_history_vin_fk;

DROP TABLE Vehicle;
