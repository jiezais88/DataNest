-- DataNest scheduler database for XXL-JOB
CREATE
DATABASE IF NOT EXISTS `datanest_scheduler` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON `datanest_scheduler`.* TO
'nacos'@'%';
FLUSH
PRIVILEGES;
