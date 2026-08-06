package com.coco.faro.diag;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Profiler de zonas: que parte del mundo te esta costando el rendimiento.
 *
 * Como funciona, y por que asi:
 *
 * Un profiler de verdad —spark, por ejemplo— instrumenta el codigo y te dice que
 * metodo consume CPU. Eso es superior y Faro no lo reemplaza; si spark esta
 * instalado, la pantalla te manda ahi. Lo que Faro aporta es otra pregunta,
 * complementaria: no "que metodo", sino "que LUGAR". Y esa se puede responder
 * bien sin instrumentar nada.
 *
 * El metodo: cada medio segundo se toma una muestra del mundo cargado y se cuenta
 * por chunk cuantas entidades y cuantos bloques con logica (block entities) hay,
 * separando los que tienen ticker —los que realmente cuestan cada tick— de los
 * que son decorativos. Al mismo tiempo se anota el tiempo de tick del cliente.
 * Cuando un tick se pasa del presupuesto, la muestra de ese momento se marca.
 *
 * Al final, los chunks que aparecen marcados una y otra vez son la zona caliente.
 * Es correlacion, no causa, y la pantalla lo dice con esas palabras. Pero cuando
 * un chunk concentra 400 entidades y aparece en el 80% de los tirones, no hay
 * mucho que discutir.
 *
 * Costo del propio profiler: una pasada por las entidades cargadas cada 500 ms.
 * Se contabiliza en el autoconsumo de Faro, que se muestra en la pestaña de
 * Rendimiento. Y viene apagado por defecto.
 */
public final class ProfilerZonas {

    /** Cada cuanto se toma una muestra del mundo. */
    private static final long INTERVALO_MS = 500L;

    /** Tick por encima de esto = el momento se marca como caliente. */
    private static final double TICK_CALIENTE_MS = 50.0;

    /** Resumen acumulado de un chunk. */
    public static final class Zona {
        final int x;
        final int z;
        int entidadesMax;
        int blockEntitiesMax;
        int blockEntitiesConTickerMax;
        int muestras;
        int muestrasCalientes;
        double sumaTickMs;
        final Map<String, Integer> porTipoEntidad = new LinkedHashMap<>();

        Zona(int x, int z) {
            this.x = x;
            this.z = z;
        }

        public int chunkX() {
            return x;
        }

        public int chunkZ() {
            return z;
        }

        public int entidades() {
            return entidadesMax;
        }

        public int blockEntities() {
            return blockEntitiesMax;
        }

        public int blockEntitiesConTicker() {
            return blockEntitiesConTickerMax;
        }

        public int muestras() {
            return muestras;
        }

        public int muestrasCalientes() {
            return muestrasCalientes;
        }

        public double tickPromedioMs() {
            return muestras == 0 ? 0 : sumaTickMs / muestras;
        }

        /** Fraccion de muestras de este chunk en que el tick se paso del limite. */
        public double fraccionCaliente() {
            return muestras == 0 ? 0 : muestrasCalientes / (double) muestras;
        }

        /** Coordenadas de bloque del centro del chunk, para poder ir a mirar. */
        public String coordenadas() {
            return (x * 16 + 8) + ", ~, " + (z * 16 + 8);
        }

        /** Los tipos de entidad que mas abundan aca. */
        public List<Map.Entry<String, Integer>> tiposDominantes(int cuantos) {
            return porTipoEntidad.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(cuantos)
                    .map(e -> (Map.Entry<String, Integer>)
                            new java.util.AbstractMap.SimpleEntry<>(e.getKey(), e.getValue()))
                    .toList();
        }

        /**
         * Puntaje de sospecha. Combina carga y coincidencia con los tirones.
         *
         * Las dos cosas tienen que darse: un chunk con muchas entidades que nunca
         * coincide con un tiron no es un problema, y un chunk vacio que coincide
         * es casualidad. Multiplicarlas deja arriba solo lo que cumple ambas.
         */
        public double puntaje() {
            double carga = entidadesMax + blockEntitiesConTickerMax * 3.0;
            return carga * (0.25 + fraccionCaliente());
        }
    }

    private static final ProfilerZonas INSTANCIA = new ProfilerZonas();

    private final Map<Long, Zona> zonas = new LinkedHashMap<>();
    private volatile boolean activo = false;
    private volatile long ultimaMuestra = 0L;
    private volatile int muestrasTotales = 0;
    private volatile int muestrasCalientesTotales = 0;
    private volatile long inicioMs = 0L;

    private ProfilerZonas() {
    }

    public static ProfilerZonas get() {
        return INSTANCIA;
    }

    public boolean activo() {
        return activo;
    }

    public void activar(boolean v) {
        this.activo = v;
        if (v && inicioMs == 0L) {
            inicioMs = System.currentTimeMillis();
        }
    }

    public void reiniciar() {
        synchronized (zonas) {
            zonas.clear();
        }
        muestrasTotales = 0;
        muestrasCalientesTotales = 0;
        inicioMs = System.currentTimeMillis();
    }

    public int muestrasTotales() {
        return muestrasTotales;
    }

    public long segundosMidiendo() {
        return inicioMs == 0 ? 0 : (System.currentTimeMillis() - inicioMs) / 1000L;
    }

    /**
     * Toma una muestra si toca. Se llama desde el tick del cliente.
     *
     * @param tickMs cuanto duro el ultimo tick, para marcar los momentos calientes
     */
    public void muestrear(double tickMs) {
        if (!activo) {
            return;
        }
        long ahora = System.currentTimeMillis();
        if (ahora - ultimaMuestra < INTERVALO_MS) {
            return;
        }
        ultimaMuestra = ahora;

        long t0 = System.nanoTime();
        try {
            tomarMuestra(tickMs);
        } catch (Throwable ignored) {
            // Recorrer el mundo mientras cambia puede fallar. Una muestra perdida
            // no importa; tumbar el tick del cliente si.
        } finally {
            MonitorHardware.get().registrarTrabajoPropio(System.nanoTime() - t0);
        }
    }

    private void tomarMuestra(double tickMs) {
        Minecraft mc = Minecraft.getInstance();
        Level nivel = mc.level;
        if (nivel == null) {
            return;
        }
        boolean caliente = tickMs >= TICK_CALIENTE_MS;
        muestrasTotales++;
        if (caliente) {
            muestrasCalientesTotales++;
        }

        Map<Long, int[]> conteoEntidades = new LinkedHashMap<>();
        Map<Long, Map<String, Integer>> tipos = new LinkedHashMap<>();

        for (Entity e : nivel.entitiesForRendering()) {
            if (e == null || !e.isAlive()) {
                continue;
            }
            long clave = ChunkPos.asLong(
                    e.blockPosition().getX() >> 4, e.blockPosition().getZ() >> 4);
            conteoEntidades.computeIfAbsent(clave, k -> new int[1])[0]++;
            tipos.computeIfAbsent(clave, k -> new LinkedHashMap<>())
                    .merge(nombreDe(e), 1, Integer::sum);
        }

        // Block entities: se recorren los chunks cargados alrededor del jugador.
        // Ir mas alla del radio de simulacion no aporta: esos no ticken.
        Map<Long, int[]> conteoBloques = new LinkedHashMap<>();
        if (mc.player != null) {
            int cx = mc.player.blockPosition().getX() >> 4;
            int cz = mc.player.blockPosition().getZ() >> 4;
            int radio = 8;
            for (int dx = -radio; dx <= radio; dx++) {
                for (int dz = -radio; dz <= radio; dz++) {
                    ChunkAccess acceso = nivel.getChunk(cx + dx, cz + dz,
                            net.minecraft.world.level.chunk.ChunkStatus.FULL, false);
                    if (!(acceso instanceof LevelChunk chunk)) {
                        continue;
                    }
                    long clave = ChunkPos.asLong(cx + dx, cz + dz);
                    int[] v = conteoBloques.computeIfAbsent(clave, k -> new int[2]);
                    for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                        v[0]++;
                        if (tickea(nivel, e.getValue())) {
                            v[1]++;
                        }
                    }
                }
            }
        }

        synchronized (zonas) {
            java.util.Set<Long> tocados = new java.util.LinkedHashSet<>();
            tocados.addAll(conteoEntidades.keySet());
            tocados.addAll(conteoBloques.keySet());

            for (Long clave : tocados) {
                Zona z = zonas.computeIfAbsent(clave,
                        k -> new Zona(ChunkPos.getX(k), ChunkPos.getZ(k)));
                z.muestras++;
                z.sumaTickMs += tickMs;
                if (caliente) {
                    z.muestrasCalientes++;
                }
                int[] ents = conteoEntidades.get(clave);
                if (ents != null) {
                    z.entidadesMax = Math.max(z.entidadesMax, ents[0]);
                }
                int[] bloques = conteoBloques.get(clave);
                if (bloques != null) {
                    z.blockEntitiesMax = Math.max(z.blockEntitiesMax, bloques[0]);
                    z.blockEntitiesConTickerMax = Math.max(z.blockEntitiesConTickerMax, bloques[1]);
                }
                Map<String, Integer> t = tipos.get(clave);
                if (t != null) {
                    for (Map.Entry<String, Integer> e : t.entrySet()) {
                        z.porTipoEntidad.merge(e.getKey(), e.getValue(), Math::max);
                    }
                }
            }

            // Tope de memoria: con vuelo creativo se pueden tocar miles de chunks.
            // Se descartan los de menor puntaje, que son justo los que no interesan.
            if (zonas.size() > 400) {
                List<Map.Entry<Long, Zona>> ordenadas = new ArrayList<>(zonas.entrySet());
                ordenadas.sort(Comparator.comparingDouble(e -> e.getValue().puntaje()));
                for (int i = 0; i < 100; i++) {
                    zonas.remove(ordenadas.get(i).getKey());
                }
            }
        }
    }

    /**
     * true si ese bloque ejecuta logica cada tick.
     *
     * La distincion vale oro: 500 cofres no cuestan nada, 50 maquinas si. Se
     * consulta con tipos crudos porque la firma de getTicker es generica sobre el
     * tipo concreto del block entity, que aca no se conoce en compilacion.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean tickea(Level nivel, BlockEntity be) {
        if (be == null) {
            return false;
        }
        try {
            if (be.getBlockState().getBlock()
                    instanceof net.minecraft.world.level.block.EntityBlock bloque) {
                return ((net.minecraft.world.level.block.EntityBlock) bloque).getTicker(
                        nivel, be.getBlockState(),
                        (net.minecraft.world.level.block.entity.BlockEntityType) be.getType()) != null;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String nombreDe(Entity e) {
        try {
            var id = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
            return id == null ? e.getType().toString() : id.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Zonas ordenadas por sospecha. */
    public List<Zona> calientes(int cuantas) {
        synchronized (zonas) {
            List<Zona> out = new ArrayList<>(zonas.values());
            out.sort(Comparator.comparingDouble(Zona::puntaje).reversed());
            return out.subList(0, Math.min(cuantas, out.size()));
        }
    }

    /** Suma de entidades por tipo en todo lo muestreado: quien llena el mundo. */
    public List<Map.Entry<String, Integer>> tiposGlobales(int cuantos) {
        Map<String, Integer> total = new LinkedHashMap<>();
        synchronized (zonas) {
            for (Zona z : zonas.values()) {
                for (Map.Entry<String, Integer> e : z.porTipoEntidad.entrySet()) {
                    total.merge(e.getKey(), e.getValue(), Integer::sum);
                }
            }
        }
        return total.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(cuantos)
                .map(e -> (Map.Entry<String, Integer>)
                        new java.util.AbstractMap.SimpleEntry<>(e.getKey(), e.getValue()))
                .toList();
    }

    public String veredicto() {
        if (!activo) {
            return "Apagado. Prendelo, jugá unos minutos en la zona donde sentis los tirones "
                    + "y volvé a mirar.";
        }
        if (muestrasTotales < 10) {
            return "Midiendo... jugá un rato para tener muestras suficientes.";
        }
        List<Zona> top = calientes(1);
        if (top.isEmpty()) {
            return "Sin zonas cargadas para analizar.";
        }
        Zona z = top.get(0);
        if (muestrasCalientesTotales == 0) {
            return String.format(Locale.ROOT,
                    "%d muestras y ningun tick por encima de 50 ms. No hay zona caliente: "
                            + "el rendimiento aca esta bien.", muestrasTotales);
        }
        return String.format(Locale.ROOT,
                "El chunk en %s concentra %d entidades y %d bloques con logica, y aparece en "
                        + "el %.0f%% de los tirones. Es correlacion, no prueba — pero es por donde empezar.",
                z.coordenadas(), z.entidades(), z.blockEntitiesConTicker(),
                z.fraccionCaliente() * 100);
    }
}
