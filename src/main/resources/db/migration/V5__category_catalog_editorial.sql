ALTER TABLE categories
    ADD COLUMN catalog_headline varchar(200),
    ADD COLUMN catalog_description varchar(500),
    ADD CONSTRAINT categories_catalog_headline_plain_text CHECK (catalog_headline IS NULL OR catalog_headline !~ '[<>]'),
    ADD CONSTRAINT categories_catalog_description_plain_text CHECK (catalog_description IS NULL OR catalog_description !~ '[<>]');

UPDATE categories SET
    catalog_headline = 'Imprime tus ideas.',
    catalog_description = 'Soluciones de impresión para interiores y exteriores.'
WHERE slug = 'impresion-gran-formato';

UPDATE categories SET
    catalog_headline = 'Haz visible tu marca.',
    catalog_description = 'Letreros y soluciones para fachadas, negocios y espacios comerciales.'
WHERE slug = 'letreros-publicitarios';

UPDATE categories SET
    catalog_headline = 'Personaliza lo que quieras.',
    catalog_description = 'Productos personalizados para tu marca, negocio o evento.'
WHERE slug = 'merchandising';

UPDATE categories SET
    catalog_headline = 'Tu marca también está en los detalles.',
    catalog_description = 'Tarjetas, volantes, dípticos, calendarios y papelería corporativa.'
WHERE slug = 'imprenta-papeleria';

UPDATE categories SET
    catalog_headline = 'Comunica, orienta y destaca.',
    catalog_description = 'Señalización personalizada para empresas y espacios.'
WHERE slug = 'senaletica';

UPDATE categories SET
    catalog_headline = 'Soluciones integrales a la medida de tu marca.',
    catalog_description = 'Diseño, producción e instalación en un solo lugar.'
WHERE slug = 'branding-instalaciones';
