-- Which branch of the store a receipt is from, as printed under its name: "11620 Monument Dr, Fairfax, VA 22030".
-- The store stays the chain ("Wegmans"), so prices still compare across its branches.
ALTER TABLE receipt ADD COLUMN store_location VARCHAR(160);
