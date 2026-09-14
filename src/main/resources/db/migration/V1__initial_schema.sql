CREATE TABLE categories (
    id varchar(64) PRIMARY KEY,
    name varchar(160) NOT NULL CHECK (btrim(name) <> ''),
    slug varchar(160) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    description text,
    active boolean NOT NULL DEFAULT true,
    display_order integer NOT NULL DEFAULT 0 CHECK (display_order >= 0),
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp
);
CREATE TABLE products (
    id varchar(64) PRIMARY KEY,
    category_id varchar(64) NOT NULL REFERENCES categories(id) ON DELETE RESTRICT,
    name varchar(200) NOT NULL CHECK (btrim(name) <> ''),
    slug varchar(160) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    short_description varchar(500) NOT NULL,
    description text NOT NULL,
    sale_type varchar(16) NOT NULL CHECK (sale_type IN ('QUANTITY', 'PACK', 'AREA')),
    unit_label varchar(40) NOT NULL CHECK (btrim(unit_label) <> ''),
    pack_size integer CHECK (pack_size > 0),
    pack_label varchar(40),
    min_quantity integer CHECK (min_quantity > 0),
    quantity_step integer CHECK (quantity_step > 0),
    featured boolean NOT NULL DEFAULT false,
    published boolean NOT NULL DEFAULT false,
    display_order integer NOT NULL DEFAULT 0 CHECK (display_order >= 0),
    seo_title varchar(200),
    seo_description varchar(500),
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp,
    CONSTRAINT product_pack_configuration CHECK (
        (sale_type = 'PACK' AND pack_size IS NOT NULL AND pack_label IS NOT NULL AND btrim(pack_label) <> '')
        OR (sale_type <> 'PACK' AND pack_size IS NULL AND pack_label IS NULL)
    )
);
CREATE TABLE product_materials (
    id varchar(64) PRIMARY KEY,
    product_id varchar(64) NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    name varchar(160) NOT NULL CHECK (btrim(name) <> ''),
    active boolean NOT NULL DEFAULT true,
    display_order integer NOT NULL DEFAULT 0 CHECK (display_order >= 0),
    UNIQUE(product_id, name)
);
CREATE TABLE product_extras (
    id varchar(64) PRIMARY KEY,
    product_id varchar(64) NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    name varchar(160) NOT NULL CHECK (btrim(name) <> ''),
    active boolean NOT NULL DEFAULT true,
    display_order integer NOT NULL DEFAULT 0 CHECK (display_order >= 0),
    UNIQUE(product_id, name)
);
CREATE TABLE product_images (
    id varchar(64) PRIMARY KEY,
    product_id varchar(64) NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    url varchar(2048) NOT NULL CHECK (btrim(url) <> ''),
    alt_text varchar(300) NOT NULL,
    primary_image boolean NOT NULL DEFAULT false,
    display_order integer NOT NULL DEFAULT 0 CHECK (display_order >= 0)
);
CREATE INDEX idx_categories_active_order ON categories(active, display_order, id);
CREATE INDEX idx_products_category ON products(category_id);
CREATE INDEX idx_products_published_order ON products(published, display_order, id);
CREATE INDEX idx_products_featured ON products(featured) WHERE published;
CREATE INDEX idx_products_display_order ON products(display_order);
CREATE INDEX idx_materials_product_order ON product_materials(product_id, display_order, id);
CREATE INDEX idx_extras_product_order ON product_extras(product_id, display_order, id);
CREATE INDEX idx_images_product_order ON product_images(product_id, display_order, id);
CREATE UNIQUE INDEX idx_images_one_primary ON product_images(product_id) WHERE primary_image;
CREATE FUNCTION set_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = current_timestamp;
    RETURN NEW;
END;
$$;
CREATE TRIGGER categories_updated BEFORE UPDATE ON categories FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER products_updated BEFORE UPDATE ON products FOR EACH ROW EXECUTE FUNCTION set_updated_at();
