CREATE SEQUENCE customer_number_seq START WITH 1003;

CREATE TABLE customer (
    id                 BIGSERIAL PRIMARY KEY,
    customer_id        VARCHAR(20)  NOT NULL UNIQUE,
    first_name         VARCHAR(100) NOT NULL,
    last_name          VARCHAR(100) NOT NULL,
    email              VARCHAR(254) NOT NULL,
    phone              VARCHAR(20)  NOT NULL,
    date_of_birth      DATE         NOT NULL,
    national_id_masked VARCHAR(20),
    status             VARCHAR(20)  NOT NULL,
    kyc_status         VARCHAR(20)  NOT NULL,
    kyc_verified_at    TIMESTAMPTZ,
    email_opt_in       BOOLEAN      NOT NULL DEFAULT TRUE,
    sms_opt_in         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    version            BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_customer_email ON customer (LOWER(email));
