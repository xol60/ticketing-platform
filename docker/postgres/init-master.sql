-- Create per-service databases
CREATE DATABASE auth_db;
CREATE DATABASE ticket_db;
CREATE DATABASE order_db;
CREATE DATABASE saga_db;
CREATE DATABASE pricing_db;
CREATE DATABASE reservation_db;
CREATE DATABASE payment_db;
CREATE DATABASE notification_db;
CREATE DATABASE agent_db;
-- Keep secondary_market_db LAST: both postgres healthchecks probe for it as the
-- sentinel that this whole script finished. A new database added after it would
-- let the healthcheck go green before that database exists.
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
GRANT CONNECT ON DATABASE agent_db            TO ticketing;
GRANT CONNECT ON DATABASE secondary_market_db TO ticketing;
