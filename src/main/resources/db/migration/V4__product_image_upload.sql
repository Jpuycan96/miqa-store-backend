-- Preserve legacy references and retain image records after removal.
ALTER TABLE product_images ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE product_images ADD COLUMN storage_key varchar(200);
ALTER TABLE product_images ADD CONSTRAINT image_inactive_not_primary CHECK (active OR NOT primary_image);
