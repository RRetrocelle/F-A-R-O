package com.coco.faro.diag;

import com.coco.faro.repair.RegistroAcciones;
import com.coco.faro.repair.ServicioReparacion;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orquestador. Junta todas las piezas y las mantiene vivas durante la sesion.
 *
 * El trabajo pesado —abrir ~190 zips, leer sus mods.toml, construir el grafo de
 * dependencias y parsear el crash report— se hace UNA vez, en un hilo daemon de
 * prioridad minima, y queda cacheado. La interfaz nunca toca el disco mientras
 * dibuja. En una CPU de 5a generacion eso es la diferencia entre un mod que
 * ayuda y uno que estorba.
 */
public final class MotorDiagnostico {

    private static MotorDiagnostico instancia;

    private final Path carpetaJuego;
    private final Path carpetaMods;
    private final Path carpetaCrashReports;
    private final Path carpetaFaro;

    private final RegistroAcciones registro;
    private final ServicioReparacion reparacion;
    private final VigilanteLog vigilante;
    private final MonitorRendimiento rendimiento = new MonitorRendimiento();

    private volatile List<MetadatosJar> jars = List.of();
    private volatile List<Problema> problemas = List.of();
    private volatile InventarioMods inventario;
    private volatile Diagnostico diagnostico;

    private final AtomicBoolean listo = new AtomicBoolean(false);
    private final AtomicBoolean enCurso = new AtomicBoolean(false);
    private volatile long duracionAnalisisMs = 0L;

    private MotorDiagnostico(Path carpetaJuego) {
        this.carpetaJuego = carpetaJuego;
        this.carpetaMods = carpetaJuego.resolve("mods");
        this.carpetaCrashReports = carpetaJuego.resolve("crash-reports");
        this.carpetaFaro = carpetaJuego.resolve("faro");
        this.registro = new RegistroAcciones(carpetaFaro);
        this.reparacion = new ServicioReparacion(registro);
        this.vigilante = new VigilanteLog(carpetaJuego.resolve("logs").resolve("latest.log"));
    }

    public static synchronized MotorDiagnostico crear(Path carpetaJuego) {
        if (instancia == null) {
            instancia = new MotorDiagnostico(carpetaJuego);
        }
        return instancia;
    }

    public static MotorDiagnostico get() {
        return instancia;
    }

    /** Lanza el analisis completo en segundo plano. Seguro de llamar varias veces. */
    public void analizarEnSegundoPlano() {
        if (listo.get() || !enCurso.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(this::correrAnalisis, "Faro-Analisis");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void correrAnalisis() {
        long inicio = System.currentTimeMillis();
        try {
            // 1. Leer todos los jars de la carpeta.
            List<MetadatosJar> escaneados = EscanerJars.escanear(carpetaMods);

            // 2. Chequeos preventivos: duplicados, loader, dependencias.
            List<Problema> detectados = AnalizadorDependencias.analizar(escaneados);

            // 3. Cruzar con lo que Forge cargo de verdad.
            InventarioMods inv = InventarioMods.construir(carpetaMods, escaneados);

            // 4. Analizar el ultimo crash, si lo hay, con todo el contexto anterior.
            Diagnostico diag = LectorCrashReports.masReciente(carpetaCrashReports)
                    .map(archivo -> LectorCrashReports.analizar(
                            archivo, escaneados, detectados, vigilante.rankingOrigenes(10)))
                    .orElseGet(Diagnostico::sinCrash);

            // Si el crash es de una sesion anterior y TODOS sus sospechosos ya
            // no estan instalados, se considera resuelto y deja de reportarse.
            // Sin esto, un crash viejo ya arreglado sigue alarmando para siempre.
            if (diag.huboCrash() && todosLosSospechososRemovidos(diag, escaneados)) {
                diag = Diagnostico.sinCrash();
            }

            this.jars = escaneados;
            this.problemas = detectados;
            this.inventario = inv;
            this.diagnostico = diag;
        } catch (Throwable e) {
            this.jars = List.of();
            this.problemas = List.of();
            this.inventario = null;
            this.diagnostico = Diagnostico.sinCrash();
        } finally {
            duracionAnalisisMs = System.currentTimeMillis() - inicio;
            listo.set(true);
            enCurso.set(false);
        }
    }

    /**
     * true si ningun sospechoso del crash sigue instalado.
     *
     * Exige que haya habido al menos un sospechoso: un crash sin culpable
     * identificado no se puede dar por resuelto solo porque no encontramos a
     * nadie, seria esconder un problema real.
     */
    private static boolean todosLosSospechososRemovidos(Diagnostico diag,
                                                        List<MetadatosJar> jars) {
        if (diag.ranking().isEmpty()) {
            return false;
        }
        java.util.Set<String> presentes = new java.util.HashSet<>();
        for (MetadatosJar j : jars) {
            for (String id : j.todosLosModIds()) {
                presentes.add(id.toLowerCase());
            }
        }
        return diag.ranking().stream()
                .noneMatch(s -> presentes.contains(s.modId().toLowerCase()));
    }

    public void reanalizar() {
        listo.set(false);
        analizarEnSegundoPlano();
    }

    public boolean listo() {
        return listo.get();
    }

    public long duracionAnalisisMs() {
        return duracionAnalisisMs;
    }

    // ------------------------------------------------------------ resultados

    public List<MetadatosJar> jars() {
        return jars;
    }

    public List<Problema> problemas() {
        return problemas;
    }

    /** Problemas de severidad CRITICA o ALTA, que son los que hay que mirar ya. */
    public List<Problema> problemasSerios() {
        return problemas.stream()
                .filter(p -> p.severidad() == Severidad.CRITICA || p.severidad() == Severidad.ALTA)
                .toList();
    }

    public Optional<InventarioMods> inventario() {
        return Optional.ofNullable(inventario);
    }

    public Optional<Diagnostico> diagnostico() {
        return Optional.ofNullable(diagnostico);
    }

    public VigilanteLog vigilante() {
        return vigilante;
    }

    public MonitorRendimiento rendimiento() {
        return rendimiento;
    }

    public ServicioReparacion reparacion() {
        return reparacion;
    }

    public RegistroAcciones registro() {
        return registro;
    }

    // --------------------------------------------------------------- rutas

    public Path carpetaJuego() {
        return carpetaJuego;
    }

    public Path carpetaMods() {
        return carpetaMods;
    }

    public Path carpetaLogs() {
        return carpetaJuego.resolve("logs");
    }

    public Path carpetaCrashReports() {
        return carpetaCrashReports;
    }

    public Path carpetaFaro() {
        return carpetaFaro;
    }

    /**
     * Resumen de una linea para la pestana principal y el boton del menu.
     * Prioriza lo mas grave: primero problemas criticos, despues el crash,
     * despues los errores del log.
     */
    public String titular() {
        if (!listo()) {
            return "Analizando la instalacion...";
        }
        long criticos = problemas.stream().filter(p -> p.severidad() == Severidad.CRITICA).count();
        if (criticos > 0) {
            return criticos + (criticos == 1 ? " problema critico" : " problemas criticos")
                    + " que van a romper el arranque";
        }
        long altos = problemas.stream().filter(p -> p.severidad() == Severidad.ALTA).count();
        if (altos > 0) {
            return altos + (altos == 1 ? " problema importante" : " problemas importantes") + " para revisar";
        }
        if (diagnostico != null && diagnostico.huboCrash()) {
            return "Hubo un crash: " + diagnostico.tipo().titulo().toLowerCase();
        }
        if (vigilante.errores() > 0) {
            return vigilante.errores() + " errores en el log de esta sesion";
        }
        return "Todo en orden. No detecte problemas.";
    }

    public int colorTitular() {
        if (!listo()) {
            return 0xFF58A6FF;
        }
        if (problemas.stream().anyMatch(p -> p.severidad() == Severidad.CRITICA)) {
            return 0xFFF85149;
        }
        if (problemas.stream().anyMatch(p -> p.severidad() == Severidad.ALTA)
                || (diagnostico != null && diagnostico.huboCrash())) {
            return 0xFFD29922;
        }
        return 0xFF3FB950;
    }
}
