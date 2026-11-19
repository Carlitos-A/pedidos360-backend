-- Ejecutar en pedidosdb después de desplegar las nuevas columnas y con respaldo previo.
-- No asumir zona horaria para fecha histórica: created_at_utc queda NULL.
SELECT id, total FROM pedido
WHERE total IS NULL OR total < 0 OR total > 9007199254740991 OR total <> FLOOR(total);

START TRANSACTION;
UPDATE pedido SET total_clp = CAST(total AS SIGNED)
WHERE total_clp IS NULL AND total >= 0 AND total <= 9007199254740991 AND total = FLOOR(total);
COMMIT;

SELECT COUNT(*) AS pedidos_sin_total_clp FROM pedido WHERE total_clp IS NULL;
SELECT COUNT(*) AS fechas_historicas_sin_zona FROM pedido
WHERE created_at_utc IS NULL AND fecha IS NOT NULL;
