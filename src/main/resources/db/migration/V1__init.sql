-- Used Items Log schema.
-- Written to run on PostgreSQL (production) and H2 in PostgreSQL mode (tests).

create table app_user (
    id            uuid primary key,
    email         varchar(254) not null,
    display_name  varchar(60)  not null,
    password_hash varchar(100) not null,
    created_at    timestamp with time zone not null,
    constraint uq_app_user_email unique (email)
);

create table household (
    id          uuid primary key,
    name        varchar(80) not null,
    invite_code varchar(16) not null,
    created_at  timestamp with time zone not null,
    constraint uq_household_invite_code unique (invite_code)
);

create table household_member (
    id           uuid primary key,
    household_id uuid not null references household (id) on delete cascade,
    user_id      uuid not null references app_user (id) on delete cascade,
    role         varchar(16) not null,
    joined_at    timestamp with time zone not null,
    constraint uq_household_member unique (household_id, user_id)
);

create index ix_household_member_user on household_member (user_id);

create table usage_entry (
    id           uuid primary key,
    household_id uuid not null references household (id) on delete cascade,
    user_id      uuid references app_user (id) on delete set null,
    item_name    varchar(80)    not null,
    item_key     varchar(80)    not null,
    quantity     numeric(12, 2) not null,
    unit         varchar(20)    not null,
    category     varchar(30)    not null,
    note         varchar(140),
    used_at      timestamp with time zone not null,
    created_at   timestamp with time zone not null,
    constraint ck_usage_entry_quantity check (quantity > 0)
);

-- Month views and totals filter by household and time range.
create index ix_usage_entry_household_used_at on usage_entry (household_id, used_at);
create index ix_usage_entry_household_item on usage_entry (household_id, item_key);
