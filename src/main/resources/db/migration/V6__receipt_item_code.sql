-- The item code some stores print on each receipt line. Safeway prints the UPC without its two small end
-- digits ("2113050504" for UPC 0 21130 50504 3); others print the full UPC. A scanned barcode matches
-- these, so a product can be linked to its receipt line without naming it.
alter table receipt_item add column item_code varchar(14);
create index ix_receipt_item_household_code on receipt_item (household_id, item_code);
