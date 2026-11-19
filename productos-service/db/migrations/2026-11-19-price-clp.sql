-- Ejecutar en productosdb después de desplegar la columna price_clp y con respaldo previo.
-- Los precios fraccionarios o inválidos necesitan revisión; no se redondean.
SELECT id, precio FROM producto
WHERE precio IS NULL OR precio < 0 OR precio > 9007199254740991 OR precio <> FLOOR(precio);

START TRANSACTION;
UPDATE producto SET price_clp = CAST(precio AS SIGNED)
WHERE price_clp IS NULL AND precio >= 0 AND precio <= 9007199254740991 AND precio = FLOOR(precio);
COMMIT;

SELECT COUNT(*) AS productos_sin_precio_clp FROM producto WHERE price_clp IS NULL;
