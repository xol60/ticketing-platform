-- Create per-service databases
CREATE DATABASE auth_db;
CREATE DATABASE ticket_db;
CREATE DATABASE order_db;
CREATE DATABASE saga_db;
CREATE DATABASE pricing_db;
CREATE DATABASE reservation_db;
CREATE DATABASE payment_db;
CREATE DATABASE notification_db;
CREATE DATABASE secondary_market_db;

-- Replication user for slave
CREATE ROLE replicator WITH REPLICATION PASSWORD 'replicator_secret' LOGIN;

-- Grant connect on all databases
GRANT CONNECT ON DATABASE auth_db             TO ticketing;
GRANT CONNECT ON DATABASE ticket_db           TO ticketing;
GRANT CONNECT ON DATABASE order_db            TO ticketing;
GRANT CONNECT ON DATABASE saga_db             TO ticketing;
GRANT CONNECT ON DATABASE pricing_db          TO ticketing;
GRANT CONNECT ON DATABASE reservation_db      TO ticketing;
GRANT CONNECT ON DATABASE payment_db          TO ticketing;
GRANT CONNECT ON DATABASE notification_db     TO ticketing;
GRANT CONNECT ON DATABASE secondary_market_db TO ticketing;
