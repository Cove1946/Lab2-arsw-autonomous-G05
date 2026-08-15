# ADR-001: Concurrency control for warehouse shared state

## Context

Simulador de un centro de distribución donde N robots, modelados como platform threads, comparten cuatro objetos mutables: `PackageQueue`, `DeliveryRegistry`, `WarehouseStatistics` y `SimulationControl`. El código de partida no sincronizaba ninguno de ellos:

- check-then-act en `PackageQueue.takeNext()` (`isEmpty()` → `get(0)` → `remove(0)`);
- read-modify-write no atómico en `WarehouseStatistics.recordProcessed()`;
- `deliveries.add()` concurrente sobre un `ArrayList` en `DeliveryRegistry.register()`;
- espera activa (`Thread.onSpinWait()`) en `SimulationControl.awaitIfPaused()`;
- reporte final impreso tras un `Thread.sleep(60)`, antes de que los robots terminaran.

La evidencia está en las secciones 3 y 10 del informe: el 100% de las corridas resultaban anómalas en las tres cargas probadas, con paquetes duplicados y perdidos, y con `ArrayIndexOutOfBoundsException` matando hilos de robot.

Restricciones del laboratorio: no eliminar la concurrencia, no usar pools ni virtual threads, y no resolverlo con un único candado global.

## Decision

Monitor intrínseco (`synchronized`) sobre un objeto `lock` privado por clase, con la región crítica mínima que cubra la operación lógica completa, y `wait()` / `notifyAll()` sobre ese mismo lock privado para pausa y reanudación.

| Clase | Bajo el lock privado |
|---|---|
| `PackageQueue` | `takeNext()` y `pendingCount()` |
| `DeliveryRegistry` | `register()` y `snapshot()` |
| `WarehouseStatistics` | `recordProcessed()` y ambos getters |
| `SimulationControl` | `awaitIfPaused()`, `pause()`, `resume()` e `isPaused()` |

La terminación se coordina con `Thread.join()` a través de `WarehouseSimulation.awaitCompletion()`.

El procesamiento del paquete (`WarehouseRobot.process()`, que hace `Thread.sleep()`) queda deliberadamente **fuera** de todo candado: es lo que evita que la sincronización serialice la simulación completa.

## Alternatives considered

1. **`AtomicInteger` / `AtomicLong` con CAS** para los contadores y para `nextPosition`. Descartada: da atomicidad por campo, no por operación lógica. `DeliveryRegistry` necesita que el incremento de la posición y la inserción en la lista sean una sola unidad, y en `WarehouseStatistics` dos atómicos independientes nunca garantizan un instante en el que ambos correspondan al mismo conjunto de paquetes.

2. **`ReentrantLock` + `Condition`.** Descartada: aporta `tryLock` con timeout, adquisición interrumpible y varias condiciones, capacidades que aquí no se usan, a cambio de liberación manual en `finally` y más superficie de error (un `return` o una excepción sin `finally` dejaría el candado tomado para siempre).

3. **`synchronized` a nivel de método (sobre `this`).** Descartada por encapsulación: el monitor de `this` es público de hecho, así que cualquier código externo podría tomarlo y bloquear el sistema. El lock privado hace auditable, leyendo una sola clase, quién puede adquirirlo.

## Quality attributes affected

- **Correctitud y fiabilidad** (atributo priorizado): el resultado deja de depender del scheduling.
- **Rendimiento / throughput**: impacto acotado, porque las regiones críticas duran órdenes de magnitud menos que el procesamiento por paquete, que corre fuera del candado.
- **Mantenibilidad**: la sincronización queda encapsulada dentro de cada clase; `WarehouseRobot` no tuvo que cambiar.
- **Escalabilidad**: locks por objeto en vez de uno global, así que solo compiten entre sí los robots que tocan la misma clase en ese instante.

## Evidence

- 300 corridas de `RaceConditionProbe` (100 por cada carga: 8/100, 16/250 y 32/500) con **0 anomalías**, frente al 100% de fallas antes del cambio.
- El reporte final se imprime una sola vez y siempre con `pending=0` y `processedCounter == registry == initialParcels`.
- Durante la pausa el snapshot mantiene `pendientes + procesados = paquetes iniciales` (56 + 124 = 180 en la corrida documentada en la sección 9.3).
- `mvn clean test`: `Tests run: 2, Failures: 0, Errors: 0` · `BUILD SUCCESS`.

## Consequences

Cada operación sobre un objeto compartido se serializa, así que existe un punto de contención por objeto, siendo la cola el más cargado. Es aceptable porque la sección crítica es mucho más corta que el procesamiento del paquete.

Los getters también toman el lock: eso garantiza visibilidad de memoria al hilo coordinador, pero implica que tomar un snapshot bajo carga bloquea brevemente a los robots.

Punto importante: la consistencia del snapshot compuesto **no** la dan los locks — son cuatro candados distintos y no existe atomicidad global entre ellos. La da el hecho de que todos los robots están detenidos en un punto seguro (pausa) o ya terminados (`join()`).

## Risks

- `snapshot()` compone cuatro locks independientes: si en el futuro se leyera sin pausa y sin `join()`, podría mezclar estados de instantes distintos.
- El `wait()` debe permanecer dentro de un `while`. Si alguien lo cambiara a `if`, un despertar espurio dejaría continuar a un robot que debería seguir pausado.
- Un `pause()` sin `resume()` posterior deja robots dormidos indefinidamente y cuelga al coordinador, porque `join()` no tiene timeout.
- Ante una interrupción, `awaitIfPaused()` restaura el flag y retorna, de modo que el robot continúa aunque la simulación siga pausada. Es aceptable hoy porque nadie interrumpe robots, pero habría que revisarlo si se implementara cancelación real.
- Ninguna de estas garantías cruza el límite de la JVM: con varias instancias detrás de un balanceador, el invariante de negocio queda sin proteger (ver sección 14 del informe).
