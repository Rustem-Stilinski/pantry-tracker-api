-- Barcodes: which item a product's barcode is, per household ("0041303012345" is "Milk 2%, 1 gal").
-- The code is stored as GTIN-13 where possible (a 12-digit UPC-A gets a leading zero), so a code read as
-- UPC-A on one phone and EAN-13 on another is the same row. item_key matches receipt_item.item_key and
-- usage_entry.item_key, which is how a scan finds the item's prices and uses.

create table barcode_item (
    id           uuid primary key,
    household_id uuid not null references household (id) on delete cascade,
    code         varchar(14) not null,
    item_name    varchar(80) not null,
    item_key     varchar(80) not null,
    created_by   uuid references app_user (id) on delete set null,
    created_at   timestamp with time zone not null,
    updated_at   timestamp with time zone not null,
    constraint uq_barcode_item_household_code unique (household_id, code)
);
