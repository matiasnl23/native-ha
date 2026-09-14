# Rediseño del dashboard (antes del MVP3)

Plan en etapas para reemplazar el editor actual (lista de botones arriba, subir/bajar, nombre y
quitar) por edición directa sobre el dashboard, con botones redimensionables y múltiples vistas.
Cada etapa deja la app funcionando y se puede probar en la tablet antes de pasar a la siguiente.

## Decisiones tomadas

| Tema | Decisión |
|---|---|
| Posicionamiento | Cada vista tiene una **lista ordenada** de botones con ancho × alto en celdas. Un algoritmo de empaquetado denso los ubica en la grilla (rellena huecos). Reordenar = mover en la lista. |
| Espaciadores | Existe un tipo de botón **vacío** (espaciador) para separar visualmente grupos de botones (distinto dominio/área). Ocupa celdas, no muestra nada fuera del modo edición. |
| Grilla | Columnas y filas visibles sin scroll se configuran **por vista**. El tamaño de celda sale de dividir el área visible; si hay más filas, scroll vertical. |
| Guardado del modo edición | Se edita una copia de trabajo y se confirma con **Listo** o se descarta con **Cancelar**. |
| Navegación entre vistas | Swipe entre vistas y un **botón especial "link a vista"** en la grilla. |
| Grilla propia | Se reemplaza `LazyVerticalGrid` por un layout propio: `LazyVerticalGrid` soporta span de columnas pero no de filas. Con decenas de botones no hace falta que sea lazy; las miniaturas de cámara solo se refrescan si están visibles. |

### Ideas anotadas para más adelante (no ahora)

- Que un botón de entidad existente también funcione como link a una vista, asignándolo a **doble
  toque** o **mantener presionado**.

## Etapas

### Etapa 1 — Modelo de datos y migración
Sin cambios visibles. Base de todo lo demás.
- `DashboardLayout` → lista de `DashboardView` (id, nombre, configuración de grilla, botones).
- `DashboardTile` con `id` propio, tamaño (`colSpan`/`rowSpan`) y tipo: entidad (con etiqueta
  opcional), link a vista, espaciador.
- Migración de los botones actuales a una vista "Principal", sin pérdida de datos.
- Operaciones puras y testeadas: agregar/editar/mover/quitar botón; crear/renombrar/borrar/reordenar
  vista; cambiar grilla de una vista.
- Agente: `android-ui` (Sonnet).

### Etapa 2 — Motor de grilla + ajustes de la vista
- Algoritmo de empaquetado (Kotlin puro, testeado): lista ordenada + tamaños → posiciones. Spans
  mayores que las columnas se recortan.
- Composable de grilla propia: celdas según columnas/filas visibles de la vista, scroll vertical,
  miniaturas de cámara que se refrescan solo si están en pantalla.
- Ajustes de la vista: columnas y filas visibles.
- Agente: `android-ui` (Opus: layout propio y rendimiento en la tablet).

### Etapa 3 — Modo edición con modales
- Botón "Editar" en el dashboard: cada botón muestra un ícono de editar y aparece un botón **＋** al
  final.
- Modal de editar botón: nombre, tamaño (ancho/alto con vista previa), quitar. Estructura extensible
  para las opciones por tipo de la Etapa 6.
- Modal de agregar: buscador/filtros actual (límite de resultados, piso/área) extraído como
  componente reutilizable, más opciones "Espaciador" y "Link a vista".
- Listo / Cancelar.
- Agente: `android-ui` (Sonnet).

### Etapa 4 — Reordenar con drag & drop
- En modo edición: mantener presionado y arrastrar, con empaquetado en vivo, feedback háptico y
  autoscroll en los bordes.
- Se elimina la pantalla/ruta del editor viejo (queda disponible hasta acá para no perder la forma
  de reordenar).
- Agente: `android-ui` (Opus: gestos + reempaquetado en vivo con buena fluidez).

### Etapa 5 — Múltiples vistas
- `HorizontalPager` para swipe entre vistas, con indicador de página.
- Botón "link a vista" funcional.
- Gestión de vistas: crear, renombrar, borrar, reordenar.
- Recordar la última vista abierta (y, opcional en kiosko, volver a la principal tras inactividad).
- Agente: `android-ui` (Sonnet).

### Etapa 6 — Botones inteligentes por tipo (se planifica en detalle al llegar)
- Arquitectura de renderers por dominio: cómo se ve el botón, qué hace el toque y qué muestra el
  panel de detalle.
- Luces: intensidad, temperatura de color o color según `supported_color_modes`.
- Alarma: armar/desarmar (con código si hace falta). Luego climate, cover, media, etc.
- Agentes: `ha-client` (servicios/atributos) + `android-ui` (controles).

## Orden y dependencias

| Etapa | Depende de | Paralelizable |
|---|---|---|
| 1. Modelo y migración | — | No (define el contrato) |
| 2. Motor de grilla | 1 | Con la extracción del buscador de la etapa 3 |
| 3. Modo edición y modales | 1, 2 | — |
| 4. Drag & drop | 2, 3 | — |
| 5. Múltiples vistas | 1, 3 | Secuencial con 4 (ambas tocan el dashboard) |
| 6. Botones inteligentes | 3 | Aparte |
