CREATE TABLE app_user (
    id UUID NOT NULL,
    provider VARCHAR(16) NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    display_name VARCHAR(40) NOT NULL,
    avatar_url VARCHAR(1024),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_login_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT uk_app_user_provider_subject UNIQUE (provider, provider_subject)
);
