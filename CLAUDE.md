# Smartcoin

Aplicación web de presupuesto personal para planificar y seguir la economía personal mes a mes, con proyección a 24 meses. Cada usuario ve solo sus datos.

- `docs/`: definición funcional. Es la fuente de verdad.
- `smartcoin-backend/`: API REST en Spring Boot sobre MySQL. Instrucciones propias en `smartcoin-backend/CLAUDE.md`.
- `smartcoin-frontend/`: aplicación Angular para escritorio. Instrucciones propias en `smartcoin-frontend/CLAUDE.md`.

## Documentación

Vocabulario obligatorio:

@docs/glosario.md

Leer según la tarea:

| Archivo | Cuándo |
|---|---|
| `docs/historias-de-usuario.md` | Antes de empezar cualquier historia. Tiene el orden de implementación, los criterios de aceptación y los endpoints de cada épica. |
| `docs/reglas-de-negocio.md` | Antes de implementar o tocar lógica. Cada regla tiene un ID (RN-xx) que citan las historias. Al final están los códigos de error de la API. |
| `docs/modelo-de-datos.md` | Antes de crear o cambiar entidades, tablas o migraciones. |
| `docs/decisiones.md` | Cuando algo parezca raro o arbitrario: ahí está el porqué. También lista los supuestos sin confirmar y lo que está fuera de alcance. |
| `docs/openapi.json` | Contrato de la API. Lo genera el backend; nunca se edita a mano. |

## Forma de trabajo

1. **Una historia por vez, en corte vertical**: reglas con tests, endpoint, pantalla y, si corresponde, test de punta a punta. No adelantar trabajo de historias futuras.
2. **Antes de escribir código**, leer la historia, sus reglas y las tablas que toca. Si algo es ambiguo, contradice otro documento o no está cubierto, **preguntar**. No inventar reglas de negocio.
3. **Si la implementación obliga a cambiar una regla o el modelo**, proponer el cambio y esperar aprobación. Una vez aprobado, actualizar en el mismo commit los documentos afectados y `docs/decisiones.md`.
4. **Supuestos**: las decisiones marcadas como "Supuesto" en `docs/decisiones.md` se implementan tal cual, pero si una historia depende de uno, mencionarlo al presentar el plan.
5. **Nombres**: identificadores en inglés, tal como figuran en el glosario. Textos de la interfaz, mensajes para el usuario y documentación en español.
6. **Commits** chicos, con el ID de la historia al principio: `HU-19: registrar movimientos parciales`.

## Definición de terminado

Una historia está terminada cuando:

- [ ] Cumple todos sus criterios de aceptación.
- [ ] Las reglas nuevas o modificadas tienen tests unitarios, incluidos los ejemplos y casos límite que citan la historia y sus reglas.
- [ ] Pasan `./mvnw test` en `smartcoin-backend/` y `npm test` en `smartcoin-frontend/`.
- [ ] Si cambió la API: `docs/openapi.json` regenerado y cliente del frontend regenerado.
- [ ] Si tiene pantalla: probada en el navegador en el flujo principal y en al menos un caso de error.
- [ ] Los documentos reflejan lo implementado.

## Reglas que no se rompen

- **La base `smartcoin` es la única y tiene datos reales.** Nunca ejecutar `flyway clean`, `DROP`, `TRUNCATE` ni borrados masivos, y nunca configurar `spring.jpa.hibernate.ddl-auto` con un valor distinto de `validate`.
- **Los tests del backend no tocan la base.** Los de punta a punta sí escriben a través de la aplicación, pero siempre con un usuario de prueba propio (ver `smartcoin-frontend/CLAUDE.md`), nunca con el usuario real.
- **Nunca modificar una migración ya aplicada.** Los cambios de esquema van en una migración nueva. Antes de una migración que borre o transforme datos existentes, avisar y esperar confirmación.
- **Sin secretos en el repositorio.** Contraseñas, `APP_JWT_SECRET` y `APP_ADMIN_KEY` van en variables de entorno o en archivos ignorados por git.
- **Dinero**: `BigDecimal` en Java y `DECIMAL(19,2)` en la base; nunca `double` ni `float`. El frontend no hace cuentas con montos: los totales los calcula el backend.
- **ARS y USD nunca se suman entre sí.** Todos los totales van separados por moneda.
- **Toda consulta filtra por el usuario autenticado.** Un recurso de otro usuario responde 404.
- **Fechas de negocio sin hora** (`LocalDate`, `YearMonth`). "Hoy" se obtiene de un `Clock` con zona `America/Argentina/Mendoza`, nunca de `LocalDate.now()` sin argumento.

## Fuera de alcance por ahora

Docker, Testcontainers, PostgreSQL, versión móvil, compras individuales con tarjeta, feriados, presupuestos compartidos y conversión entre monedas. La lista completa está en `docs/decisiones.md`. No agregar nada de esto sin que se pida.
