-- Prices: stores, receipts and the lines on them.
-- receipt_item repeats household_id, store_id and purchased_at from its receipt so price history,
-- store comparison and spending queries read one table.

create table store (
    id           uuid primary key,
    household_id uuid not null references household (id) on delete cascade,
    name         varchar(80) not null,
    name_key     varchar(80) not null,
    created_at   timestamp with time zone not null,
    constraint uq_store_household_name unique (household_id, name_key)
);

create table receipt (
    id           uuid primary key,
    household_id uuid not null references household (id) on delete cascade,
    store_id     uuid not null references store (id),
    purchased_at timestamp with time zone not null,
    subtotal     numeric(12, 2),
    tax          numeric(12, 2),
    total        numeric(12, 2) not null,
    created_by   uuid references app_user (id) on delete set null,
    created_at   timestamp with time zone not null
);

create index ix_receipt_household_purchased_at on receipt (household_id, purchased_at);

create table receipt_item (
    id           uuid primary key,
    receipt_id   uuid not null references receipt (id) on delete cascade,
    household_id uuid not null references household (id) on delete cascade,
    store_id     uuid not null references store (id),
    purchased_at timestamp with time zone not null,
    line_no      integer not null,
    item_name    varchar(80) not null,
    item_key     varchar(80) not null,
    receipt_text varchar(120),
    quantity     numeric(12, 3) not null,
    unit         varchar(20) not null,
    unit_price   numeric(12, 3) not null,
    line_total   numeric(12, 2) not null,
    category     varchar(30) not null,
    constraint ck_receipt_item_quantity check (quantity > 0),
    constraint ck_receipt_item_prices check (unit_price >= 0 and line_total >= 0)
);

create index ix_receipt_item_receipt on receipt_item (receipt_id);
create index ix_receipt_item_household_item on receipt_item (household_id, item_key, purchased_at);
