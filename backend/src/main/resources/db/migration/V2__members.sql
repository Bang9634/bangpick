CREATE TABLE members (
    id CHAR(36) PRIMARY KEY,
    identity_provider VARCHAR(40) NOT NULL,
    provider_subject VARBINARY(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    UNIQUE (identity_provider, provider_subject),
    CHECK (role IN ('USER', 'ADMIN'))
);
