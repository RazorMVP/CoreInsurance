ALTER TABLE customer_directors ADD COLUMN IF NOT EXISTS created_by VARCHAR(255);
ALTER TABLE customer_documents ADD COLUMN IF NOT EXISTS created_by VARCHAR(255);
