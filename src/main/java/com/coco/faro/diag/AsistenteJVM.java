package com.coco.faro.diag;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Recomienda cuanta RAM asignarle al juego y con que argumentos de JVM.
 *
 * La parte de RECOMENDAR es directa: se lee la RAM fisica del equipo, la
 * asignada actualmente y la cantidad de mods, y se calculan los argumentos.
 *
 * La parte de APLICAR no se hace desde aca a proposito. Esos argumentos viven en
 * la configuracion de la instancia de SKLauncher, no en la sesion que ya esta
 * corriendo: cambiarlos en caliente no tendria ningun efecto, y el launcher
 * puede pisar el archivo. Faro muestra exactamente que poner y donde, y el
 * cambio lo hace el usuario con el juego cerrado.
 */
public final class AsistenteJVM {

    public record Recomendacion(
            long ramFisicaMB,
            long ramAsignadaMB,
            long ramRecomendadaMB,
            int cantidadMods,
            String veredicto,
            List<String> argumentos,
            boolean hayQueCambiar) {
    }

    private AsistenteJVM() {
    }

    /** RAM que la JVM tiene asignada ahora mismo (-Xmx). */
    public static long ramAsignadaMB() {
        return Runtime.getRuntime().maxMemory() / (1024 * 1024);
    }

    /**
     * Cuanta RAM conviene asignar.
     *
     * Dos reglas que no se negocian, porque asignar de mas empeora las cosas:
     *   - nunca mas de la mitad de la RAM fisica, para que le quede al sistema;
     *   - tope de 8 GB. Arriba de eso las pausas del recolector de basura se
     *     alargan y el juego tironea MAS, no menos. Es el error mas comun.
     */
    public static long recomendarMB(long ramFisicaMB, int cantidadMods) {
        long base;
        if (cantidadMods <= 50) {
            base = 3072;
        } else if (cantidadMods <= 120) {
            base = 4096;
        } else if (cantidadMods <= 200) {
            base = 6144;
        } else {
            base = 8192;
        }
        if (ramFisicaMB > 0) {
            base = Math.min(base, ramFisicaMB / 2);
        }
        base = Math.min(base, 8192);
        return Math.max(2048, (base / 512) * 512);
    }

    /**
     * Argumentos de JVM al estilo de los "Aikar's flags", escalados a la memoria.
     *
     * Estan pensados para que el recolector de basura haga pausas cortas y
     * frecuentes en vez de pausas largas: en un juego, un frenon de 300 ms se
     * siente mucho peor que veinte pausas de 5 ms.
     */
    public static List<String> argumentosPara(long ramMB) {
        List<String> args = new ArrayList<>();
        args.add("-Xms" + ramMB + "M");
        args.add("-Xmx" + ramMB + "M");
        args.add("-XX:+UseG1GC");
        args.add("-XX:+ParallelRefProcEnabled");
        args.add("-XX:MaxGCPauseMillis=200");
        args.add("-XX:+UnlockExperimentalVMOptions");
        args.add("-XX:+DisableExplicitGC");
        args.add("-XX:+AlwaysPreTouch");

        // Con 12 GB o mas conviene subir el tamano de region y las reservas.
        boolean grande = ramMB >= 12288;
        args.add("-XX:G1NewSizePercent=" + (grande ? "40" : "30"));
        args.add("-XX:G1MaxNewSizePercent=" + (grande ? "50" : "40"));
        args.add("-XX:G1HeapRegionSize=" + (grande ? "16M" : "8M"));
        args.add("-XX:G1ReservePercent=" + (grande ? "15" : "20"));
        args.add("-XX:G1HeapWastePercent=5");
        args.add("-XX:G1MixedGCCountTarget=4");
        args.add("-XX:InitiatingHeapOccupancyPercent=" + (grande ? "20" : "15"));
        args.add("-XX:G1MixedGCLiveThresholdPercent=90");
        args.add("-XX:G1RSetUpdatingPauseTimePercent=5");
        args.add("-XX:SurvivorRatio=32");
        args.add("-XX:+PerfDisableSharedMem");
        args.add("-XX:MaxTenuringThreshold=1");
        // Con muchos mods el Metaspace se llena antes que el heap.
        args.add("-XX:MaxMetaspaceSize=512M");
        return args;
    }

    public static Recomendacion analizar(int cantidadMods) {
        long fisica = MonitorHardware.get().memoriaFisicaTotalMB();
        long asignada = ramAsignadaMB();
        long recomendada = recomendarMB(fisica, cantidadMods);

        String veredicto;
        boolean cambiar = false;

        if (asignada < recomendada * 0.75) {
            veredicto = "Tenes poca RAM asignada para " + cantidadMods + " mods. "
                    + "Subirla deberia reducir los tirones.";
            cambiar = true;
        } else if (asignada > recomendada * 1.6) {
            veredicto = "Tenes MAS RAM asignada de la conveniente. Suena raro, pero "
                    + "de mas empeora: el recolector de basura tarda mas en cada "
                    + "limpieza y eso se siente como frenones.";
            cambiar = true;
        } else {
            veredicto = "La RAM asignada esta bien para este pack. No hace falta tocarla.";
        }

        if (fisica > 0 && recomendada >= fisica / 2) {
            veredicto += "  Ojo: tu equipo tiene " + (fisica / 1024) + " GB en total, "
                    + "asi que no le des mucho mas al juego o se queda corto el sistema.";
        }

        return new Recomendacion(fisica, asignada, recomendada, cantidadMods,
                veredicto, argumentosPara(recomendada), cambiar);
    }

    /** Argumentos con los que arranco esta sesion, para comparar. */
    public static List<String> argumentosActuales() {
        try {
            return new ArrayList<>(ManagementFactory.getRuntimeMXBean().getInputArguments());
        } catch (Throwable t) {
            return List.of();
        }
    }

    /**
     * Donde tiene que ir el cambio. No lo hace Faro: lo hace el usuario.
     *
     * El texto es generico a proposito: Faro no puede asumir que launcher se
     * esta usando. Cada uno guarda estos argumentos en su propio formato, y
     * escribir en el archivo equivocado seria peor que no hacer nada.
     */
    public static String dondeAplicar() {
        return "Buscá en tu launcher los ajustes de memoria o argumentos de Java de esta "
                + "instancia. En la mayoria esta como 'Java Arguments', 'JVM Arguments' o "
                + "'Memoria asignada'.\n"
                + "Hacelo con el juego CERRADO: estos valores se leen al arrancar, y varios "
                + "launchers pisan el archivo si lo editas con el juego abierto.";
    }
}
