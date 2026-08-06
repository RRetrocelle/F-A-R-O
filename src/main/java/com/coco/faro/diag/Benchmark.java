package com.coco.faro.diag;

import com.coco.faro.Faro;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Benchmark del modpack: un numero de FPS que se pueda comparar de verdad.
 *
 * El problema que resuelve: "me da 60 FPS" no significa nada solo. ¿Mirando al
 * cielo o a una base llena de maquinas? ¿Con que distancia de render? Comparar
 * dos packs, o el mismo pack antes y despues de un cambio, exige que las
 * condiciones sean identicas. Si no, el numero es folklore.
 *
 * Que estandariza la prueba, exactamente:
 *
 *   1. Fija la distancia de render, la de simulacion, las nubes, las partículas
 *      y el VSync a valores conocidos. Anota los que tenias y los restaura al
 *      terminar, pase lo que pase.
 *   2. Hace girar la camara 360 grados a velocidad constante durante la prueba,
 *      asi se recorre todo el entorno en vez de medir un solo angulo.
 *   3. Descarta los primeros segundos: al empezar a girar el juego tiene que
 *      construir mallas de chunks que todavia no habia dibujado, y eso no es
 *      representativo de como se juega.
 *   4. Reporta promedio, 1% bajo y peor cuadro. El 1% bajo es el numero que
 *      importa: son los tirones, y es lo que realmente se siente.
 *
 * Lo que NO estandariza, y por eso la pantalla lo pide explicito: el lugar. Faro
 * no puede teletransportarte sin trucos. La prueba es comparable si la corres
 * SIEMPRE EN EL MISMO PUNTO — se guarda la coordenada de cada corrida para que
 * se pueda verificar que dos resultados son comparables, y se avisa cuando no lo
 * son.
 */
public final class Benchmark {

    /** Duracion total de una corrida. */
    public static final int SEGUNDOS_TOTAL = 20;

    /** Cuanto se descarta al principio, mientras se construyen los chunks. */
    public static final int SEGUNDOS_CALENTAMIENTO = 5;

    /** Ajustes fijos de la prueba. Cambiarlos invalida comparar con corridas viejas. */
    public static final int DISTANCIA_RENDER = 8;
    public static final int DISTANCIA_SIMULACION = 8;

    public enum Estado { INACTIVO, CALENTANDO, MIDIENDO, TERMINADO, CANCELADO }

    /** Resultado de una corrida completa. */
    public record Resultado(double fpsPromedio, double fps1PorCientoBajo, double fpsPeor,
                            double fpsMejor, int cuadros, String dimension,
                            String coordenadas, long momento, int modsCargados) {

        /** Veredicto con el criterio a la vista. */
        public String veredicto() {
            if (fps1PorCientoBajo >= 50) {
                return "Va muy comodo. Incluso los peores momentos se mantienen fluidos.";
            }
            if (fps1PorCientoBajo >= 30) {
                return "Va bien. Los tirones existen pero no molestan.";
            }
            if (fps1PorCientoBajo >= 20) {
                return "Jugable, con tirones notorios. El promedio enganña: mira el 1% bajo.";
            }
            return "Los peores momentos bajan de 20 FPS. Eso se siente como frenones "
                    + "constantes, aunque el promedio parezca aceptable.";
        }

        public String comparableCon(Resultado otro) {
            if (otro == null) {
                return "";
            }
            if (!dimension.equals(otro.dimension()) || !coordenadas.equals(otro.coordenadas())) {
                return "OJO: esta corrida fue en otro lugar. Los numeros NO son comparables.";
            }
            double dif = fpsPromedio - otro.fpsPromedio();
            String signo = dif >= 0 ? "+" : "";
            return String.format(Locale.ROOT,
                    "Mismo lugar que la corrida anterior: %s%.1f FPS de promedio.", signo, dif);
        }
    }

    private static final Benchmark INSTANCIA = new Benchmark();

    private volatile Estado estado = Estado.INACTIVO;
    private volatile long inicioMs = 0L;
    private final List<Double> muestrasFps = new ArrayList<>();
    private volatile Resultado ultimo;
    private final List<Resultado> historial = new ArrayList<>();

    /** Ajustes originales, para poder devolverlos exactamente como estaban. */
    private Integer distanciaOriginal;
    private Integer simulacionOriginal;
    private Boolean vsyncOriginal;
    private Object nubesOriginal;
    private Object particulasOriginal;
    private float yawInicial;
    private float pitchInicial;

    private volatile String dimensionCorrida = "";
    private volatile String coordenadasCorrida = "";

    private Benchmark() {
    }

    public static Benchmark get() {
        return INSTANCIA;
    }

    public Estado estado() {
        return estado;
    }

    public Resultado ultimo() {
        return ultimo;
    }

    public List<Resultado> historial() {
        synchronized (historial) {
            return new ArrayList<>(historial);
        }
    }

    public boolean corriendo() {
        return estado == Estado.CALENTANDO || estado == Estado.MIDIENDO;
    }

    public int segundosRestantes() {
        if (!corriendo()) {
            return 0;
        }
        long transcurrido = (System.currentTimeMillis() - inicioMs) / 1000L;
        return (int) Math.max(0, SEGUNDOS_TOTAL - transcurrido);
    }

    public double progreso() {
        if (!corriendo()) {
            return 0;
        }
        return Math.min(1.0, (System.currentTimeMillis() - inicioMs) / (SEGUNDOS_TOTAL * 1000.0));
    }

    /** Por que no se puede correr ahora, o null si se puede. */
    public String impedimento() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return "Tenes que estar dentro de un mundo. Desde el menu no hay nada que medir.";
        }
        if (corriendo()) {
            return "Ya hay una prueba en curso.";
        }
        return null;
    }

    /**
     * Arranca la prueba.
     *
     * Guardar los ajustes ANTES de tocarlos y en campos propios, no en variables
     * locales, es lo que permite restaurarlos aunque la prueba se cancele o el
     * jugador cierre la pantalla en el medio.
     */
    public void iniciar() {
        if (impedimento() != null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();

        try {
            distanciaOriginal = mc.options.renderDistance().get();
            simulacionOriginal = mc.options.simulationDistance().get();
            vsyncOriginal = mc.options.enableVsync().get();
            nubesOriginal = mc.options.cloudStatus().get();
            particulasOriginal = mc.options.particles().get();

            mc.options.renderDistance().set(DISTANCIA_RENDER);
            mc.options.simulationDistance().set(DISTANCIA_SIMULACION);
            // VSync limitaria los FPS al refresco del monitor y taparia justo lo
            // que queremos medir.
            mc.options.enableVsync().set(false);
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            mc.options.particles().set(net.minecraft.client.ParticleStatus.MINIMAL);
            mc.levelRenderer.allChanged();

            yawInicial = mc.player.getYRot();
            pitchInicial = mc.player.getXRot();

            dimensionCorrida = mc.level.dimension().location().toString();
            coordenadasCorrida = mc.player.blockPosition().getX() + ", "
                    + mc.player.blockPosition().getY() + ", "
                    + mc.player.blockPosition().getZ();
        } catch (Throwable t) {
            Faro.LOG.error("[Faro] No pude preparar el benchmark", t);
            restaurar();
            return;
        }

        synchronized (muestrasFps) {
            muestrasFps.clear();
        }
        inicioMs = System.currentTimeMillis();
        estado = Estado.CALENTANDO;
        Faro.LOG.info("[Faro] Benchmark iniciado en {} @ {}", dimensionCorrida, coordenadasCorrida);
    }

    public void cancelar() {
        if (!corriendo()) {
            return;
        }
        estado = Estado.CANCELADO;
        restaurar();
    }

    /**
     * Avanza la prueba. Se llama una vez por cuadro desde el render.
     *
     * @param fpsActual FPS instantaneos del cuadro
     */
    public void tick(double fpsActual) {
        if (!corriendo()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            cancelar();
            return;
        }

        long transcurridoMs = System.currentTimeMillis() - inicioMs;
        double transcurridoS = transcurridoMs / 1000.0;

        // Giro constante: una vuelta completa repartida en toda la prueba.
        float vuelta = (float) (transcurridoS / SEGUNDOS_TOTAL) * 360f;
        mc.player.setYRot(yawInicial + vuelta);
        mc.player.setXRot(pitchInicial);

        if (transcurridoS < SEGUNDOS_CALENTAMIENTO) {
            estado = Estado.CALENTANDO;
            return;
        }
        estado = Estado.MIDIENDO;

        if (fpsActual > 0 && fpsActual < 5000) {
            synchronized (muestrasFps) {
                muestrasFps.add(fpsActual);
            }
        }

        if (transcurridoS >= SEGUNDOS_TOTAL) {
            terminar();
        }
    }

    private void terminar() {
        double[] datos;
        synchronized (muestrasFps) {
            datos = muestrasFps.stream().mapToDouble(Double::doubleValue).toArray();
        }
        restaurar();

        if (datos.length < 10) {
            estado = Estado.CANCELADO;
            return;
        }
        Arrays.sort(datos);

        double suma = 0;
        for (double d : datos) {
            suma += d;
        }
        double promedio = suma / datos.length;

        // 1% bajo: promedio del peor 1% de los cuadros. Con pocas muestras se
        // toma al menos uno, para no devolver 0 por division entera.
        int cuantos = Math.max(1, datos.length / 100);
        double sumaBajo = 0;
        for (int i = 0; i < cuantos; i++) {
            sumaBajo += datos[i];
        }
        double unoPorCiento = sumaBajo / cuantos;

        int mods = 0;
        try {
            MotorDiagnostico m = MotorDiagnostico.get();
            mods = m == null ? 0 : m.inventario().map(InventarioMods::cantidadCargados).orElse(0);
        } catch (Throwable ignored) {
        }

        Resultado r = new Resultado(promedio, unoPorCiento, datos[0], datos[datos.length - 1],
                datos.length, dimensionCorrida, coordenadasCorrida,
                System.currentTimeMillis(), mods);

        ultimo = r;
        synchronized (historial) {
            historial.add(r);
            while (historial.size() > 10) {
                historial.remove(0);
            }
        }
        estado = Estado.TERMINADO;
        Faro.LOG.info("[Faro] Benchmark: {} FPS promedio, {} FPS 1% bajo, {} cuadros",
                String.format(Locale.ROOT, "%.1f", promedio),
                String.format(Locale.ROOT, "%.1f", unoPorCiento), datos.length);
    }

    /**
     * Devuelve los ajustes como estaban.
     *
     * Se llama desde terminar(), desde cancelar() y desde el cierre de la
     * pantalla. Es idempotente: los campos se ponen en null despues de usarlos,
     * asi llamarla dos veces no pisa nada.
     */
    public void restaurar() {
        Minecraft mc = Minecraft.getInstance();
        try {
            if (distanciaOriginal != null) {
                mc.options.renderDistance().set(distanciaOriginal);
                distanciaOriginal = null;
            }
            if (simulacionOriginal != null) {
                mc.options.simulationDistance().set(simulacionOriginal);
                simulacionOriginal = null;
            }
            if (vsyncOriginal != null) {
                mc.options.enableVsync().set(vsyncOriginal);
                vsyncOriginal = null;
            }
            if (nubesOriginal instanceof net.minecraft.client.CloudStatus c) {
                mc.options.cloudStatus().set(c);
                nubesOriginal = null;
            }
            if (particulasOriginal instanceof net.minecraft.client.ParticleStatus p) {
                mc.options.particles().set(p);
                particulasOriginal = null;
            }
            mc.options.save();
            mc.levelRenderer.allChanged();
        } catch (Throwable t) {
            Faro.LOG.error("[Faro] No pude restaurar los ajustes despues del benchmark", t);
        }
    }

    /** Texto de estado para la pantalla. */
    public String estadoTexto() {
        return switch (estado) {
            case INACTIVO -> "Listo para empezar.";
            case CALENTANDO -> "Calentando (" + SEGUNDOS_CALENTAMIENTO
                    + " s): construyendo los chunks que faltan. Esto no se mide.";
            case MIDIENDO -> "Midiendo... " + segundosRestantes() + " s restantes. No toques nada.";
            case TERMINADO -> "Prueba terminada.";
            case CANCELADO -> "Cancelada. Los ajustes volvieron como estaban.";
        };
    }
}
