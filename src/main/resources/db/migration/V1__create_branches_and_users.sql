CREATE TABLE branches (
                          id          UUID PRIMARY KEY,
                          name        VARCHAR(100) NOT NULL,
                          code        VARCHAR(20)  NOT NULL,
                          address     VARCHAR(255),
                          department  VARCHAR(60),
                          latitude    NUMERIC(9,6) NOT NULL,
                          longitude   NUMERIC(9,6) NOT NULL,
                          active      BOOLEAN      NOT NULL DEFAULT TRUE,
                          created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
                          updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
                          CONSTRAINT uq_branches_code UNIQUE (code)
);

CREATE TABLE users (
                       id              UUID PRIMARY KEY,
                       email           VARCHAR(150) NOT NULL,
                       password_hash   VARCHAR(100) NOT NULL,
                       full_name       VARCHAR(120) NOT NULL,
                       role            VARCHAR(20)  NOT NULL,
                       branch_id       UUID REFERENCES branches (id),
                       customer_id     UUID,
                       nit             VARCHAR(20),
                       active          BOOLEAN      NOT NULL DEFAULT TRUE,
                       deactivated_by  UUID REFERENCES users (id),
                       created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
                       updated_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
                       CONSTRAINT ck_users_role CHECK (role IN ('SYSTEM_ADMIN', 'BRANCH_ADMIN', 'CASHIER', 'DRIVER', 'CUSTOMER')),
                       CONSTRAINT ck_users_branch CHECK ((role IN ('BRANCH_ADMIN', 'CASHIER')) = (branch_id IS NOT NULL)),
                       CONSTRAINT ck_users_customer CHECK ((role = 'CUSTOMER') = (customer_id IS NOT NULL)),
                       CONSTRAINT ck_users_nit CHECK (role <> 'CUSTOMER' OR nit IS NOT NULL)
);

CREATE UNIQUE INDEX uq_users_email ON users (LOWER(email));
CREATE UNIQUE INDEX uq_users_customer_id ON users (customer_id) WHERE customer_id IS NOT NULL;
CREATE INDEX ix_users_branch_role ON users (branch_id, role);