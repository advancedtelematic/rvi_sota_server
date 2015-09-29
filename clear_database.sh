#!/bin/bash
mysql -u root < drop_databases.sql
mysql -u root < create_databases.sql
sbt flywayMigrate
