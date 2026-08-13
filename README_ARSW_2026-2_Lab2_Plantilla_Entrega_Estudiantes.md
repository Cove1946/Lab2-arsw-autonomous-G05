# ARSW — Laboratorio #2
## Plantilla de entrega — Autonomous Warehouse

**Asignatura:** Arquitecturas de Software — ARSW  
**Periodo:** 2026-2  
**Laboratorio:** #2 — Autonomous Warehouse  
**Tema:** Race Conditions · Critical Sections · Thread Coordination  
**Tecnología:** Java 21 · Maven · JUnit 5  

---

## 0. Información del equipo

| Integrante                         | Código / ID | GitHub   |
|------------------------------------|-------------|----------|
| Cristian Ronaldo Guerrero Buitrago | 1000101455  | Cove1946 |
| Juan Esteban Tellez Valencia       | 1000098939  | JuanTellez125         |
|                                    |             |          |

**Repositorio:**  
`PEGAR_AQUÍ_URL_DEL_REPOSITORIO`

**Commit final:**  
`PEGAR_AQUÍ_HASH_DEL_COMMIT`

---

# 1. Evidencia de ejecución inicial

## 1.1 Verificación del entorno

Incluya la salida de:

```bash
java -version
mvn -version
```

**Evidencia:**

```text
java -version
java version "21.0.6" 2025-01-21 LTS
Java(TM) SE Runtime Environment (build 21.0.6+8-LTS-188)
Java HotSpot(TM) 64-Bit Server VM (build 21.0.6+8-LTS-188, mixed mode, sharing)
```

```text
mvn -v version
Apache Maven 3.9.12 (848fbb4bf2d427b72bdb2471c22fced7ebd9a7a1)
Maven home: C:\apache-maven-3.9.12
Java version: 21.0.6, vendor: Oracle Corporation, runtime: C:\Program Files\Java\jdk-21
Default locale: es_MX, platform encoding: UTF-8
OS name: "windows 11", version: "10.0", arch: "amd64", family: "windows"
```

---

## 1.2 Ejecución inicial

Comando utilizado:

```bash
java -cp target/classes edu.eci.arsw.warehouse.app.WarehouseMain
```

o la configuración utilizada:

```bash
java -cp target/classes edu.eci.arsw.warehouse.app.WarehouseMain <robots> <packages>
```

**Configuración utilizada:**

- Robots: 12
- Paquetes: 100

**Resultado observado:**

```text
Starting warehouse with 12 robots and 100 parcels...

--- STARTER REPORT (intentionally premature) ---
Initial parcels : 100
Pending parcels : 64
Processed count : 24
Registry size   : 24
Current leader  : Robot-07 / parcel 7 / position 1
----------------------------------------------
```

---

# 2. Estado mutable compartido

Identifique los objetos y variables compartidas entre múltiples threads.

| Objeto / Clase | Estado mutable compartido                                  | Quién lee                                                                                                                   | Quién modifica                                                           | Riesgo identificado                                                                                                                 |
|---|------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| `PackageQueue` | pending (ArrayList< Parcel >)                              | takeNext() / pending.isEmpty(), pendingCount()                                                                              | takeNext() / pending.remove(0)                                           | Entre get y remove, otro robot puede leer el mismo indice, donde dos robots pueden procesar el mismo parcel o removerlo de la lista |
| `DeliveryRegistry` | nextPosition(int), deliveries(ArrayList < DeliveryRecord>) | register() lee nextPosition, snapshot() lee deliveries                                                                      | register() incrementa nextPosition y hace deliveries.add()               | posiciones duplicadas o no contiguas. add concurrente sin sincronizar                                                               |
| `WarehouseStatistics` | processedParcels(int), totalProcessingMillis(long)         | processedParcels lee processedParcels, totalProcessingMillis lee totalProcessingMillis y recordProcessed se relee asi mismo | recordProcessed es llamado por cada robot cuando se registra una entrega | Hay un incremento atomico y el conteo final puede ser erroneo cuando dos robots terminan casi al tiempo                             |
| `SimulationControl` | paused(boolean)                                            | awaitIfPaused() y isPaused()                                                                                                          | pause() y resume() cambian el booleano                                   | Los robots pueden quedarse pausados preguntando en bucle si pueden seguir y no puedan dormirse                                      |
| Otro |                                                            |                                                                                                                             |                                                                          |                                                                                                                                     |

---

# 3. Condiciones de carrera encontradas

Documente **mínimo tres** comportamientos incorrectos o potencialmente incorrectos.

## Race Condition #1

**Clase / método involucrado:**  
`PackageQueue.takeNext()`

**Estado compartido involucrado:**  
`pending: List<Parcel>`

**Comportamiento observado:**  
`Con 500 parcelas iniciales y pending=0 al final, el registro de entregas solo tiene 490 entradas (10 parcelas nunca 
quedaron registradas) y uniqueParcels(457) < registry(490), es decir, 33 IDs de parcela quedaron duplicados.`

**¿Por qué ocurre?**  
`Lo que pasa aquí es un caso de check-then-act. Dos robots revisan casi al mismo tiempo si la lista de parcelas 
pendientes está vacía y ambos ven la misma parcela como la primera de la lista. Como hay un Thread.yield(), se aumenta 
la posibilidad de que los dos intenten retirarla al mismo tiempo. El primero elimina esa parcela, pero cuando el segundo
hace el remove(0), la lista ya cambió de posición y termina eliminando la siguiente parcela. Al final, una parcela queda
registrada como si hubiera sido procesada dos veces y otra se elimina de la lista sin que ningún robot la procese.`

**Evidencia de ejecución:**

```text
Comando: java -cp target/classes edu.eci.arsw.warehouse.verification.RaceConditionProbe 50 32 500
Run 01 -> RACE/ANOMALY | pending=0, processedCounter=484, registry=490, uniqueParcels=457, uniquePositions=452, 
positionsContiguous=false
```

---

## Race Condition #2

**Clase / método involucrado:**  
`DeliveryRegistry.register()`

**Estado compartido involucrado:**  
`deliveries: List<DeliveryRecord> (y nextPosition: int)`

**Comportamiento observado:**  
`En algunas ejecuciones, uno de los robots terminaba de forma inesperada porque ocurría una excepción que no era 
capturada. En otras corridas, en cambio, el propio robot detectaba el problema y mostraba mensajes como Queue anomaly: 
IndexOutOfBoundsException, lo que indicaba que se había producido un error al acceder a la cola compartida.`

**¿Por qué ocurre?**  
``ArrayList` no es seguro para trabajar con varios hilos al mismo tiempo. En este caso, varios robots pueden ejecutar 
`deliveries.add(...)` simultáneamente dentro de `register()` sin usar `synchronized`. Esto puede hacer que la estructura
interna del `ArrayList` quede inconsistente y provoque una excepción como `ArrayIndexOutOfBoundsException`. Como la 
excepción ocurre en `register()`, queda fuera del `try/catch` que `WarehouseRobot.run()` utiliza únicamente para 
`packageQueue.takeNext()`. Por lo tanto, el hilo del robot termina y deja de funcionar.`

**Evidencia de ejecución:**

```text
Comando: java -cp target/classes edu.eci.arsw.warehouse.verification.RaceConditionProbe 50 32 500
Exception in thread "warehouse-robot-11" java.lang.ArrayIndexOutOfBoundsException: Index 50 out of bounds for length 49
        at java.base/java.util.ArrayList.add(ArrayList.java:484)
        at edu.eci.arsw.warehouse.core.DeliveryRegistry.register(DeliveryRegistry.java:20)
        at edu.eci.arsw.warehouse.worker.WarehouseRobot.run(WarehouseRobot.java:56)
```

---

## Race Condition #3

**Clase / método involucrado:**  
`WarehouseStatistics.recordProcessed()`

**Estado compartido involucrado:**  
`processedParcels: int`

**Comportamiento observado:**  
`Con solo 500 parcelas creadas en total, la corrida terminó con processedCounter=501 y registry=506 — ambos contadores 
superan el número físico de parcelas que existieron, algo imposible en un sistema correcto.`

**¿Por qué ocurre?**  
``recordProcessed()` no realiza el incremento de forma atómica, ya que primero guarda el valor de `processedParcels`, 
luego hace `Thread.yield()` y finalmente suma 1. Si dos robots ejecutan este método al mismo tiempo, ambos pueden leer 
el mismo valor antes de actualizarlo, haciendo que uno de los incrementos se pierda. Por otro lado, si una parcela se 
procesa dos veces debido a la Race Condition #1, el contador también puede terminar siendo mayor de lo esperado porque 
la misma parcela se cuenta más de una vez.`

**Evidencia de ejecución:**

```text
Comando: java -cp target/classes edu.eci.arsw.warehouse.verification.RaceConditionProbe 50 32 500
Run 44 -> RACE/ANOMALY | pending=0, processedCounter=501, registry=506, uniqueParcels=486, uniquePositions=492, positionsContiguous=false 
```

---

# 4. Interleaving

Seleccione una de las condiciones de carrera anteriores y represente un interleaving posible.

**Condición seleccionada:**  
`PackageQueue.takeNext() — dos robots "ven" la misma caja como la primera de la fila antes de que ninguno la haya retirado.`

| Paso | Thread A                                                                               | Thread B                                                                               | Estado compartido |
|---:|----------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------|---|
| 1 | Mira la fila y ve que la primera caja es la Caja 1 (pending.isEmpty() → false)         | -                                                                                      | [Caja1, Caja2, Caja3, ...] |
| 2 | -                                                                                      | Mira la fila y también ve que la primera es la Caja 1  (pending.isEmpty() → false)     | [Caja1, Caja2, Caja3, ...] |
| 3 | Decide "me llevo la Caja 1", pero aún no la retira (selected = pending.get(0) → Caja1) | -                                                                                      | [Caja1, Caja2, Caja3, ...] |
| 4 | -                                                                                      | Decide "me llevo la Caja 1", pero aún no la retira (selected = pending.get(0) → Caja1) | [Caja1, Caja2, Caja3, ...] |
|5| Se distrae un instante y cede su turno (Thread.yield())                                | -                                                                                      | [Caja1, Caja2, Caja3, ...] |
|6| -                                                                                      | Quita la primera caja de la fila → se lleva la Caja 1 real (pending.remove(0))         | [Caja2, Caja3, ...]        |

### Explicación

¿Por qué este orden de ejecución produce un resultado incorrecto?

**Respuesta:**

`Porque entre que un robot decide cuál caja le toca (pasos 1–4) y el momento en que realmente la retira de la fila 
(pasos 5–6), pasa un instante en el que el otro robot puede alcanzar a modificar la fila. Si el sistema operativo 
hubiera dado los turnos en otro orden — por ejemplo, si A hubiera completado su remove(0) antes de que B mirara la fila 
— no habría ningún error. El resultado depende entonces del orden exacto en que el procesador reparte el tiempo entre 
los hilos, orden que cambia en cada ejecución y que el código no controla ni verifica.`


---

# 5. Invariantes del sistema

Defina las invariantes que su solución debe preservar.

## I1

`Cada paquete se procesa a lo sumo una vez: sin ella hay entregas duplicadas y perdidas de paquetes`

## I2

`Las posiciones de llegada son única: Evita orden de llegada duplicado y hace que sea observable`

## I3

`El contador de procesados debe coincidir con el numero de registros`

## I4 — opcional

`Para reportar la simulacion como completa, no deben quedar pendientes: sin ella el reporte final puede tener informacion incompleta`

---

# 6. Regiones críticas

Documente cada región crítica identificada.

| Clase | Región crítica | Invariante protegida | Mecanismo usado | ¿Por qué ese tamaño? |
|---|---|---|---|---|
|PackageQueue |Todo el cuerpo de takeNext | I1 (cada paquete se procesa a lo sumo una vez) |synchronized(lock) con lock privado |El Check-then-act deben ir en una sola operación lógicasi no, otro robot se cuela y agarra el mismo. Es rápido, no frena nada ademas el lock propio evita que algo externo se meta en el bloqueo |
|DeliveryRegistry |Bloque de register() que lee nextPosition, lo incrementa y hace deliveries.add() | I2 (posiciones de llegada únicas) |ynchronized(lock) con lock privado propio de la clase | Agrupa lectura+incremento+inserción en una operación atómica. El Lock privado evita interferencia externa.|
| WarehouseStatistics|Bloque de recordProcessed() que actualiza processedParcels y totalProcessingMillis |I3 (contador de procesados == número de registros) |synchronized(lock) con lock privado propio de la clase |Solo suma dos números, no espera nada, así que el candado se libera casi al instante. Se usa lock propio para que nada externo interfiera. |
|SimulationControl|awaitIfPaused() (espera) + pause()/resume() (cambio de bandera y señal)|I4 (snapshot/reporte consistente durante pausa)|Monitor con candado privado: robots pausados esperan con wait(), resume() los despierta con notifyAll()|Es factible igual: wait()/notify() deben invocarse sobre el mismo objeto que se usa como candado, un lock privado sirve exactamente igual que this, solo que encapsulado.|

---

# 7. Decisiones de sincronización

## 7.1 Alternativas consideradas

Marque y explique cuáles evaluaron:

- [ ] `synchronized`
- [ ] `AtomicInteger`
- [ ] Colecciones concurrentes
- [ ] `Lock`
- [ ] `wait()` / `notifyAll()`
- [ ] Otra: `________________________`

### Alternativa 1

**Descripción:**  
`________________________________________________________________________`

**Ventaja:**  
`________________________________________________________________________`

**Desventaja:**  
`________________________________________________________________________`

### Alternativa 2

**Descripción:**  
`________________________________________________________________________`

**Ventaja:**  
`________________________________________________________________________`

**Desventaja:**  
`________________________________________________________________________`

### Decisión final

**Mecanismo seleccionado:**  
`________________________________________`

**Justificación:**  
`________________________________________________________________________`

`________________________________________________________________________`

---

# 8. Finalización de threads

Explique cómo garantizaron que el programa solamente genera el reporte final cuando todos los robots han terminado.

**Mecanismo utilizado:**  
`________________________________________`

**Explicación:**  
`________________________________________________________________________`

`________________________________________________________________________`

### Pregunta

¿Por qué usar `Thread.sleep(...)` no sería una solución correcta para esperar la finalización de todos los workers?

**Respuesta:**  
`________________________________________________________________________`

---

# 9. PAUSE / RESUME

## 9.1 Problema inicial

Explique por qué el busy waiting de la implementación inicial no es adecuado.

**Respuesta:**  
`________________________________________________________________________`

`________________________________________________________________________`

---

## 9.2 Solución implementada

Explique cómo implementaron:

- `pause()`
- espera de los workers
- `resume()`
- despertar coordinado de los workers

**Respuesta:**  
`________________________________________________________________________`

`________________________________________________________________________`

---

## 9.3 Snapshot consistente

Cuando la simulación está pausada, registre:

```text
Processed parcels:
Pending parcels:
Registry size:
Current leader:
```

Explique cómo garantizan que esos valores representan un estado consistente.

**Respuesta:**  
`________________________________________________________________________`

`________________________________________________________________________`

---

# 10. Verificación con RaceConditionProbe

Ejecute:

```bash
java -cp target/classes edu.eci.arsw.warehouse.verification.RaceConditionProbe 100 32 500
```

## Resultados

| Robots | Paquetes | Runs | Anomalías antes | Anomalías después |
|---:|---:|---:|---:|---:|
| 8 | 100 | | | |
| 16 | 250 | | | |
| 32 | 500 | | | |

### Resultado final esperado

```text
Anomalous runs: 0/100
```

**Salida obtenida:**

```text
PEGAR_AQUÍ_LA_SALIDA
```

---

# 11. Evidencia de correctitud

Explique brevemente cómo demuestran que su solución es correcta.

Considere:

- invariantes;
- múltiples ejecuciones;
- distintas cargas;
- ausencia de resultados duplicados;
- ausencia de paquetes perdidos;
- finalización correcta;
- consistencia durante pausa.

**Conclusión:**

`________________________________________________________________________`

`________________________________________________________________________`

`________________________________________________________________________`

---

# 12. Impacto en atributos de calidad

| Atributo | Impacto de la solución | Evidencia / métrica |
|---|---|---|
| Correctitud / Reliability | | |
| Performance / Throughput | | |
| Maintainability | | |
| Scalability | | |

---

# 13. Trade-off principal

¿Qué ganaron y qué sacrificaron al introducir sincronización?

**Respuesta:**

`________________________________________________________________________`

`________________________________________________________________________`

---

# 14. Análisis arquitectónico

Suponga ahora que existen tres instancias de la aplicación:

```text
                 Load Balancer
                       |
            +----------+----------+
            |          |          |
          App A      App B      App C
            \          |          /
                    Database
```

## 14.1 Pregunta

¿Los bloques `synchronized` utilizados dentro de una JVM garantizan consistencia entre `App A`, `App B` y `App C`?

- [ ] Sí
- [ ] No

**Justificación:**

`________________________________________________________________________`

`________________________________________________________________________`

---

## 14.2 Evolución arquitectónica

¿Qué alternativa consideraría para garantizar consistencia entre múltiples instancias?

- [ ] Transacción en base de datos
- [ ] Restricción / constraint en base de datos
- [ ] Optimistic locking / versionado
- [ ] Lock distribuido
- [ ] Otra: `________________________`

**Decisión propuesta:**

`________________________________________________________________________`

**Justificación:**

`________________________________________________________________________`

---

# 15. Mini ADR

## ADR-001 — Concurrency control for warehouse shared state

### Context

`________________________________________________________________________`

`________________________________________________________________________`

### Decision

`________________________________________________________________________`

`________________________________________________________________________`

### Alternatives considered

1. `____________________________________________________________________`
2. `____________________________________________________________________`

### Quality attributes affected

`________________________________________________________________________`

### Evidence

`________________________________________________________________________`

### Consequences

`________________________________________________________________________`

### Risks

`________________________________________________________________________`

---

# 16. Cambios realizados

Resuma los principales cambios de código.

| Archivo / Clase | Cambio realizado | Razón |
|---|---|---|
| | | |
| | | |
| | | |
| | | |

---

# 17. Pruebas ejecutadas

| Prueba | Comando | Resultado |
|---|---|---|
| Compilación y tests | `mvn clean test` | |
| Simulación estándar | | |
| RaceConditionProbe | | |
| Pause / Resume | | |
| Otra | | |

---

# 18. Conclusiones

Incluya entre **3 y 5 conclusiones concretas**.

1. `______________________________________________________________________`
2. `______________________________________________________________________`
3. `______________________________________________________________________`
4. `______________________________________________________________________`
5. `______________________________________________________________________`

---

# 19. Checklist de entrega

- [ ] El proyecto compila con `mvn clean test`.
- [ ] El código utiliza Java 21.
- [ ] No se eliminó la concurrencia.
- [ ] No existe busy waiting en la solución final.
- [ ] El programa espera correctamente la finalización de todos los robots.
- [ ] Las regiones críticas están justificadas.
- [ ] Se preservan las invariantes definidas.
- [ ] El `RaceConditionProbe` final no presenta anomalías.
- [ ] Se documentó el análisis arquitectónico.
- [ ] Se incluyó el ADR.
- [ ] El repositorio contiene commits claros.
- [ ] Se incluyó la URL del repositorio y el commit final.

---

## Nota

No se evalúa la cantidad de texto. Se evalúa la capacidad de demostrar:

> **problema → evidencia → invariante → región crítica → decisión → implementación → verificación → trade-off arquitectónico**
