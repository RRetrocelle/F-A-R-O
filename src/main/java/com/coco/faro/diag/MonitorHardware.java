package com.coco.faro.diag;

import com.coco.faro.Faro;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lectura de CPU y GPU.
 *
 * CPU: Java la expone de forma nativa via com.sun.management.OperatingSystemMXBean.
 * Es un dato confiable y sin dependencias.
 *
 * GPU: no hay forma nativa en Java. El unico camino razonablemente confiable es
 * invocar nvidia-smi, que viene con el driver de NVIDIA. Para AMD e Intel no hay
 * un equivalente que se pueda asumir instalado.
 *
 * Por eso esta clase distingue tres estados y NUNCA inventa un numero: o hay un
 * dato real, o dice que no esta disponible en esta GPU. Mostrar un porcentaje
 * estimado seria peor que no mostrar nada, porque el usuario tomaria decisiones
 * sobre su pack en base a un numero falso.
 */
public final class MonitorHardware {

    /** Estado de la lectura de GPU. */
    public enum EstadoGpu {
        NO_PROBADO("sin probar todavia"),
        DISPONIBLE("disponible"),
        SIN_HERRAMIENTA("no disponible en esta GPU"),
        ERROR("no se pudo leer");

        public final String etiqueta;

        EstadoGpu(String etiqueta) {
            this.etiqueta = etiqueta;
        }
    }

    public record LecturaGpu(EstadoGpu estado, String nombre, int usoPorcentaje,
                             int memoriaUsadaMB, int memoriaTotalMB,
                             int temperaturaC, String detalle) {

        public boolean hayDato() {
            return estado == EstadoGpu.DISPONIBLE;
        }

        /** -1 cuando el driver no reporta temperatura. Nunca se estima. */
        public boolean hayTemperatura() {
            return hayDato() && temperaturaC > 0;
        }
    }

    private static final MonitorHardware INSTANCIA = new MonitorHardware();

    private final AtomicBoolean consultandoGpu = new AtomicBoolean(false);
    private volatile LecturaGpu gpu =
            new LecturaGpu(EstadoGpu.NO_PROBADO, "", -1, -1, -1, -1, "");
    private volatile long ultimaConsultaGpu = 0L;

    /** Cada cuanto despierta el muestreador. Ver iniciarMuestreo(). */
    private static final long INTERVALO_MUESTREO_MS = 1000L;

    private Object osBean;
    private java.lang.reflect.Method mProceso;
    private java.lang.reflect.Method mSistema;
    private java.lang.reflect.Method mMemoriaFisica;

    private volatile boolean muestreando = false;
    private volatile int cpuJuego = -1;
    private volatile int cpuSistema = -1;
    private volatile long memoriaFisicaTotalMB = -1L;

    private volatile long nanosGastadosPorFaro = 0L;
    private volatile long muestrasTomadas = 0L;

    /** Ventana de 5 segundos para promediar la CPU. Ver el bucle de muestreo. */
    private static final int VENTANA_CPU = 5;
    private final int[] ventanaJuego = new int[VENTANA_CPU];
    private final int[] ventanaSistema = new int[VENTANA_CPU];
    private int posVentana = 0;
    private volatile int muestrasEnVentana = 0;

    private int promedio(int[] v) {
        int n = Math.max(1, muestrasEnVentana);
        int suma = 0;
        for (int i = 0; i < n; i++) {
            suma += v[i];
        }
        return suma / n;
    }

    /**
     * true solo si la CPU viene alta de forma SOSTENIDA.
     *
     * Exige la ventana completa llena y un promedio por encima del umbral, para
     * que un pico aislado no dispare una alerta que asusta sin motivo. Este era
     * el falso positivo: el juego iba a 200 FPS y la alarma decia 100%.
     */
    public boolean cpuSostenidamenteAlta(int umbral) {
        return muestrasEnVentana >= VENTANA_CPU && cpuJuego >= umbral;
    }

    private MonitorHardware() {
        prepararCpu();
    }

    public static MonitorHardware get() {
        return INSTANCIA;
    }

    // ------------------------------------------------------------------- CPU

    /**
     * Se resuelve por reflexion contra com.sun.management.OperatingSystemMXBean.
     *
     * Es una clase del JDK de Oracle/OpenJDK, presente en todos los runtimes que
     * usa Minecraft, pero no forma parte de la API estandar de Java. Accederla por
     * reflexion evita que el mod no compile o reviente en una JVM donde no este.
     */
    private void prepararCpu() {
        try {
            osBean = ManagementFactory.getOperatingSystemMXBean();
            Class<?> clase = Class.forName("com.sun.management.OperatingSystemMXBean");
            if (!clase.isInstance(osBean)) {
                osBean = null;
                return;
            }
            mProceso = clase.getMethod("getProcessCpuLoad");
            try {
                mMemoriaFisica = clase.getMethod("getTotalMemorySize");
            } catch (NoSuchMethodException e) {
                // Antes de Java 14 se llamaba distinto.
                mMemoriaFisica = clase.getMethod("getTotalPhysicalMemorySize");
            }
            mSistema = clase.getMethod("getCpuLoad");
        } catch (Throwable t) {
            try {
                // getCpuLoad se llamaba getSystemCpuLoad antes de Java 14.
                Class<?> clase = Class.forName("com.sun.management.OperatingSystemMXBean");
                mSistema = clase.getMethod("getSystemCpuLoad");
            } catch (Throwable t2) {
                osBean = null;
            }
        }
    }

    /**
     * Uso de CPU del proceso de Minecraft, 0..100. -1 si todavia no hay dato.
     *
     * Devuelve el valor CACHEADO por el muestreador. Es importante que sea asi:
     * getProcessCpuLoad() calcula el uso ocurrido desde la llamada anterior, de
     * modo que invocarlo por frame deja intervalos de milisegundos y la JVM
     * responde 0 o ruido. Ese era exactamente el bug: el numero se leia bien,
     * pero se preguntaba demasiado seguido.
     */
    public int cpuDelJuego() {
        return cpuJuego;
    }

    /** Uso de CPU de todo el sistema, 0..100. -1 si todavia no hay dato. */
    public int cpuDelSistema() {
        return cpuSistema;
    }

    /**
     * Arranca el muestreador. Un hilo daemon de prioridad minima que despierta
     * una vez por segundo, toma las lecturas y las guarda.
     *
     * Un segundo es intervalo de sobra para que las mediciones de CPU sean
     * estables, y el costo de despertar un hilo por segundo es despreciable.
     */
    public synchronized void iniciarMuestreo() {
        if (muestreando) {
            return;
        }
        muestreando = true;

        Thread t = new Thread(() -> {
            while (muestreando) {
                long t0 = System.nanoTime();
                try {
                    int juego = leerCpu(mProceso);
                    int sistema = leerCpu(mSistema);
                    memoriaFisicaTotalMB = leerMemoriaFisica();

                    // Promedio movil en vez del valor instantaneo. Una sola
                    // lectura salta con cualquier pico transitorio (cargar un
                    // chunk, un GC) y deja la alarma prendida aunque el equipo
                    // este comodo. Lo que importa es el uso sostenido.
                    if (juego >= 0) {
                        ventanaJuego[posVentana] = juego;
                        ventanaSistema[posVentana] = Math.max(0, sistema);
                        posVentana = (posVentana + 1) % VENTANA_CPU;
                        if (muestrasEnVentana < VENTANA_CPU) {
                            muestrasEnVentana++;
                        }
                        cpuJuego = promedio(ventanaJuego);
                        cpuSistema = sistema >= 0 ? promedio(ventanaSistema) : -1;
                    }
                } catch (Throwable ignored) {
                }
                // Se contabiliza lo que cuesta el propio muestreo, para poder
                // mostrarlo despues: Faro tiene que poder auditarse a si mismo.
                nanosGastadosPorFaro += System.nanoTime() - t0;
                muestrasTomadas++;

                try {
                    Thread.sleep(INTERVALO_MUESTREO_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "Faro-MuestreoHardware");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    public void detenerMuestreo() {
        muestreando = false;
    }

    /** RAM fisica total del equipo en MB, o -1. Sirve para recomendar cuanta asignar. */
    public long memoriaFisicaTotalMB() {
        return memoriaFisicaTotalMB;
    }

    private long leerMemoriaFisica() {
        if (osBean == null || mMemoriaFisica == null) {
            return -1L;
        }
        try {
            Object v = mMemoriaFisica.invoke(osBean);
            return (v instanceof Long l) ? l / (1024 * 1024) : -1L;
        } catch (Throwable t) {
            return -1L;
        }
    }

    // -------------------------------------------------- auto-medicion de Faro

    /**
     * Cuanto tiempo de CPU consumio Faro en sus propios hilos, en milisegundos.
     *
     * Existe porque una herramienta que mide rendimiento tiene que poder
     * demostrar que no es parte del problema. Se muestra en el panel de
     * Rendimiento junto al resto.
     */
    public double milisegundosGastadosPorFaro() {
        return nanosGastadosPorFaro / 1_000_000.0;
    }

    public long muestrasTomadas() {
        return muestrasTomadas;
    }

    /** Suma tiempo consumido por otra tarea de Faro, para el total auditable. */
    public void registrarTrabajoPropio(long nanos) {
        nanosGastadosPorFaro += nanos;
    }

    private int leerCpu(java.lang.reflect.Method metodo) {
        if (osBean == null || metodo == null) {
            return -1;
        }
        try {
            Object v = metodo.invoke(osBean);
            if (!(v instanceof Double d)) {
                return -1;
            }
            // La primera lectura suele devolver un valor negativo hasta que la
            // JVM junta dos muestras. Eso no es 0%: es "todavia no se".
            if (d < 0) {
                return -1;
            }
            return (int) Math.round(d * 100.0);
        } catch (Throwable t) {
            return -1;
        }
    }

    public int nucleos() {
        return Runtime.getRuntime().availableProcessors();
    }

    // ------------------------------------------------------------------- GPU

    public LecturaGpu gpu() {
        return gpu;
    }

    // -------------------------------------------------- temperatura de CPU

    private volatile int tempCpu = -1;
    private volatile boolean tempCpuProbada = false;

    /** Temperatura de CPU en grados, o -1 si no se puede leer en este equipo. */
    public int temperaturaCpu() {
        return tempCpu;
    }

    public boolean temperaturaCpuProbada() {
        return tempCpuProbada;
    }

    /**
     * Intenta leer la temperatura del procesador por WMI (Windows).
     *
     * Es EXPERIMENTAL y falla en la mayoria de los equipos, a proposito
     * documentado: Java no tiene forma nativa de leer sensores, y el proveedor
     * WMI estandar (MSAcpi_ThermalZoneTemperature) depende de que la placa madre
     * lo exponga, cosa que muchas no hacen. Herramientas como LibreHardwareMonitor
     * leen el sensor directo, pero no se puede asumir que esten instaladas.
     *
     * Si no hay dato confiable se devuelve -1 y la interfaz dice "no disponible"
     * en vez de inventar un numero.
     */
    public void intentarTemperaturaCpu() {
        if (tempCpuProbada) {
            return;
        }
        tempCpuProbada = true;

        Thread t = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                        "(Get-CimInstance -Namespace root/wmi "
                                + "-ClassName MSAcpi_ThermalZoneTemperature "
                                + "-ErrorAction SilentlyContinue | "
                                + "Select-Object -First 1).CurrentTemperature");
                pb.redirectErrorStream(true);
                Process p = pb.start();

                String salida;
                try (BufferedReader rd = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    salida = rd.readLine();
                }
                if (!p.waitFor(6, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return;
                }
                if (salida == null || salida.isBlank()) {
                    return;
                }
                // WMI devuelve decimas de Kelvin.
                int decimasKelvin = Integer.parseInt(salida.trim());
                int celsius = (decimasKelvin / 10) - 273;
                // Rango de cordura: fuera de esto el sensor esta mintiendo.
                if (celsius > 0 && celsius < 130) {
                    tempCpu = celsius;
                }
            } catch (Throwable ignored) {
                // Sin dato confiable: queda en -1 y se informa como no disponible.
            }
        }, "Faro-TempCPU");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /**
     * Consulta nvidia-smi en segundo plano, como maximo una vez cada 2 segundos.
     *
     * Lanzar un proceso externo no es gratis, asi que no se hace por frame ni por
     * tick: solo cuando la pestana de rendimiento esta abierta y con este limite.
     */
    public void actualizarGpuSiCorresponde() {
        long ahora = System.currentTimeMillis();
        if (ahora - ultimaConsultaGpu < 2000L) {
            return;
        }
        if (gpu.estado() == EstadoGpu.SIN_HERRAMIENTA) {
            return; // ya sabemos que no esta; no insistimos cada 2 segundos
        }
        if (!consultandoGpu.compareAndSet(false, true)) {
            return;
        }
        ultimaConsultaGpu = ahora;

        Thread t = new Thread(() -> {
            try {
                gpu = consultarNvidiaSmi();
            } catch (Throwable e) {
                gpu = new LecturaGpu(EstadoGpu.ERROR, "", -1, -1, -1, -1, e.toString());
            } finally {
                consultandoGpu.set(false);
            }
        }, "Faro-GPU");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static LecturaGpu consultarNvidiaSmi() {
        // La temperatura viene en el mismo comando: no cuesta una llamada extra.
        ProcessBuilder pb = new ProcessBuilder(
                "nvidia-smi",
                "--query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu",
                "--format=csv,noheader,nounits");
        pb.redirectErrorStream(true);

        Process proceso;
        try {
            proceso = pb.start();
        } catch (Throwable t) {
            // El ejecutable no existe: no es NVIDIA, o el driver no lo instalo.
            return new LecturaGpu(EstadoGpu.SIN_HERRAMIENTA, "", -1, -1, -1, -1,
                    "nvidia-smi no esta disponible");
        }

        try {
            String primeraLinea;
            try (BufferedReader rd = new BufferedReader(
                    new InputStreamReader(proceso.getInputStream(), StandardCharsets.UTF_8))) {
                primeraLinea = rd.readLine();
            }
            if (!proceso.waitFor(4, TimeUnit.SECONDS)) {
                proceso.destroyForcibly();
                return new LecturaGpu(EstadoGpu.ERROR, "", -1, -1, -1, -1,
                        "nvidia-smi no respondio");
            }
            if (primeraLinea == null || primeraLinea.isBlank()) {
                return new LecturaGpu(EstadoGpu.ERROR, "", -1, -1, -1, -1, "salida vacia");
            }

            String[] campos = primeraLinea.split(",");
            if (campos.length < 4) {
                return new LecturaGpu(EstadoGpu.ERROR, "", -1, -1, -1, -1,
                        "formato inesperado: " + primeraLinea.trim());
            }
            return new LecturaGpu(
                    EstadoGpu.DISPONIBLE,
                    campos[0].trim(),
                    entero(campos[1]),
                    entero(campos[2]),
                    entero(campos[3]),
                    campos.length >= 5 ? entero(campos[4]) : -1,
                    "nvidia-smi");
        } catch (Throwable t) {
            Faro.LOG.debug("[Faro] nvidia-smi fallo: {}", t.toString());
            return new LecturaGpu(EstadoGpu.ERROR, "", -1, -1, -1, -1, t.toString());
        }
    }

    private static int entero(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return -1;
        }
    }
}
