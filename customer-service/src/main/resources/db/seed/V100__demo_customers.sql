-- Demo data only (profile "demo"). Customer ids match the customer_id claim of the Keycloak demo users.
INSERT INTO customer (customer_id, first_name, last_name, email, phone, date_of_birth, national_id_masked,
                      status, kyc_status, kyc_verified_at, created_at, updated_at)
VALUES ('CUST1001', 'Asha', 'Sharma', 'asha.sharma@example.com', '+919800000001', '1990-04-12', '********1234',
        'ACTIVE', 'VERIFIED', now(), now(), now()),
       ('CUST1002', 'Ravi', 'Kumar', 'ravi.kumar@example.com', '+919800000002', '1988-11-03', '********5678',
        'ACTIVE', 'VERIFIED', now(), now(), now());

-- Publish CustomerRegistered for the seeded customers so downstream projections (notification-service) get them.
INSERT INTO outbox_event (id, topic, message_key, event_type, aggregate_type, aggregate_id, payload, status, attempts, created_at)
SELECT e.id, 'customer-events', c.customer_id, 'CustomerRegistered', 'Customer', c.customer_id,
       json_build_object(
           'eventId', e.id, 'eventType', 'CustomerRegistered', 'schemaVersion', 1,
           'aggregateType', 'Customer', 'aggregateId', c.customer_id,
           'correlationId', 'seed-' || c.customer_id, 'producer', 'customer-service',
           'occurredAt', to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
           'payload', json_build_object(
               'customerId', c.customer_id, 'fullName', c.first_name || ' ' || c.last_name,
               'email', c.email, 'phone', c.phone, 'status', c.status, 'kycStatus', c.kyc_status,
               'emailOptIn', c.email_opt_in, 'smsOptIn', c.sms_opt_in,
               'occurredAt', to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')))::text,
       'PENDING', 0, now()
FROM customer c
CROSS JOIN LATERAL (SELECT gen_random_uuid() AS id) e;
