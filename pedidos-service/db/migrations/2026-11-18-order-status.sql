-- Ejecutar una vez sobre la base de pedidos, con respaldo previo.
-- Revisar esta consulta antes y después: los valores no reconocidos requieren decisión manual.
SELECT estado, COUNT(*) AS pedidos
FROM pedido
WHERE estado IS NULL OR UPPER(TRIM(estado)) NOT IN
    ('CREATED', 'ACCEPTED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED',
     'PENDIENTE', 'EN_PREPARACION', 'ENTREGADO')
GROUP BY estado;

START TRANSACTION;
UPDATE pedido SET estado = 'CREATED' WHERE UPPER(TRIM(estado)) = 'PENDIENTE';
UPDATE pedido SET estado = 'IN_PROGRESS' WHERE UPPER(TRIM(estado)) = 'EN_PREPARACION';
UPDATE pedido SET estado = 'COMPLETED' WHERE UPPER(TRIM(estado)) = 'ENTREGADO';
UPDATE pedido SET version = 0 WHERE version IS NULL;
COMMIT;

-- Confirmar que los tres valores antiguos ya no existen.
SELECT estado, COUNT(*) AS pedidos FROM pedido GROUP BY estado ORDER BY estado;
