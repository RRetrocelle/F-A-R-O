package com.coco.faro.diag;

import com.coco.faro.Faro;
import com.coco.faro.net.ClienteModrinth;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Actualizador integrado: dice si cada mod instalado tiene una version mas nueva.
 *
 * Como se identifica un mod sin ambiguedad — que es el problema real:
 *
 * Buscar por nombre falla seguido. Hay mods homonimos, slugs que no coinciden con
 * el modId, y forks. Modrinth resuelve esto con una consulta por HASH: se calcula
 * el SHA-1 del .jar y se pregunta {@code /version_file/<sha1>}. La respuesta es
 * la version exacta de ese archivo exacto, sin margen de error. Si el archivo no
 * esta en Modrinth (viene de CurseForge, o es un fork propio), la respuesta es
 * "no lo conozco" — y eso tambien es informacion util y honesta, mucho mejor que
 * ofrecer un mod parecido.
 *
 * Reglas que se respetan siempre:
 *   - Lo unico que sale de la PC es el hash del archivo. Ni la lista de mods, ni
 *     rutas, ni datos del sistema.
 *   - Nada se descarga sin que el usuario apriete el boton de ESE mod puntual.
 *   - La consulta se hace bajo pedido, no automaticamente al abrir el juego.
 *   - Se respeta el ritmo de la API: las consultas van de a una con pausa.
 */
public final class ActualizadorMods {

    public enum Estado {
        SIN_CONSULTAR("sin consultar"),
        CONSULTANDO("consultando..."),
        AL_DIA("al dia"),
        HAY_ACTUALIZACION("hay version nueva"),
        DESCONOCIDO("no esta en Modrinth"),
        ERROR("no se pudo consultar");

        public final String etiqueta;

        Estado(String etiqueta) {
            this.etiqueta = etiqueta;
        }
    }

    /** Lo que se sabe de un mod respecto a su actualizacion. */
    public record Info(String modId, Path jar, Estado estado, String versionInstalada,
                       String versionNueva, ClienteModrinth.Candidato candidato, String nota) {
    }

    /** Pausa entre consultas. La API de Modrinth pide no pasar de ~300/minuto. */
    private static final long PAUSA_MS = 250L;

    private static final Map<String, Info> CACHE = new LinkedHashMap<>();
    private static final AtomicInteger enCurso = new AtomicInteger(0);
    private static volatile int totalAConsultar = 0;
    private static volatile int yaConsultados = 0;

    private ActualizadorMods() {
    }

    public static Info estadoDe(MetadatosJar jar) {
        Info i = CACHE.get(clave(jar));
        if (i != null) {
            return i;
        }
        return new Info(jar.modIdPrincipal(), jar.archivo(), Estado.SIN_CONSULTAR,
                jar.version(), "", null, "");
    }

    public static boolean consultando() {
        return enCurso.get() > 0;
    }

    public static int progreso() {
        return yaConsultados;
    }

    public static int total() {
        return totalAConsultar;
    }

    public static int conActualizacion() {
        return (int) CACHE.values().stream()
                .filter(i -> i.estado() == Estado.HAY_ACTUALIZACION).count();
    }

    public static List<Info> todos() {
        return List.copyOf(CACHE.values());
    }

    public static void limpiar() {
        CACHE.clear();
        yaConsultados = 0;
        totalAConsultar = 0;
    }

    private static String clave(MetadatosJar jar) {
        return jar.archivo().toString();
    }

    /**
     * Consulta un solo mod. Es lo que dispara el boton "Actualizar" de una fila.
     *
     * @param alTerminar se llama con el resultado, desde el hilo de la consulta
     */
    public static void consultar(MetadatosJar jar, Runnable alTerminar) {
        if (jar == null) {
            return;
        }
        CACHE.put(clave(jar), new Info(jar.modIdPrincipal(), jar.archivo(), Estado.CONSULTANDO,
                jar.version(), "", null, ""));

        Thread t = new Thread(() -> {
            enCurso.incrementAndGet();
            try {
                CACHE.put(clave(jar), consultarSincronico(jar));
            } finally {
                enCurso.decrementAndGet();
                if (alTerminar != null) {
                    alTerminar.run();
                }
            }
        }, "Faro-Actualizacion");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Consulta TODOS los mods, uno por uno y con pausa.
     *
     * Se hace en un solo hilo y en serie a proposito: lanzar 190 peticiones en
     * paralelo haria que Modrinth nos corte por exceso de consultas, y ademas
     * saturaria la conexion del usuario mientras juega.
     */
    public static void consultarTodos(List<MetadatosJar> jars, Runnable alProgresar) {
        if (consultando()) {
            return;
        }
        List<MetadatosJar> aConsultar = jars.stream()
                .filter(j -> !j.sinMetadatosDeMod())
                .toList();

        totalAConsultar = aConsultar.size();
        yaConsultados = 0;

        Thread t = new Thread(() -> {
            enCurso.incrementAndGet();
            try {
                for (MetadatosJar j : aConsultar) {
                    CACHE.put(clave(j), consultarSincronico(j));
                    yaConsultados++;
                    if (alProgresar != null) {
                        alProgresar.run();
                    }
                    try {
                        Thread.sleep(PAUSA_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } finally {
                enCurso.decrementAndGet();
            }
        }, "Faro-ActualizacionMasiva");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static Info consultarSincronico(MetadatosJar jar) {
        String modId = jar.modIdPrincipal();
        String instalada = jar.version();

        String sha1;
        try {
            sha1 = com.coco.faro.repair.InstaladorMods.sha1(jar.archivo());
        } catch (Throwable t) {
            return new Info(modId, jar.archivo(), Estado.ERROR, instalada, "", null,
                    "No pude calcular el hash del archivo.");
        }

        ClienteModrinth.PorHash porHash = ClienteModrinth.identificarPorHash(sha1);
        if (porHash == null) {
            return new Info(modId, jar.archivo(), Estado.DESCONOCIDO, instalada, "", null,
                    "Este .jar no esta en Modrinth. Puede venir de CurseForge, ser un build "
                            + "propio, o una version modificada. No puedo decir si hay algo mas nuevo.");
        }

        ClienteModrinth.Candidato ultima =
                ClienteModrinth.ultimaVersionDeProyecto(porHash.idProyecto(), modId).orElse(null);
        if (ultima == null) {
            return new Info(modId, jar.archivo(), Estado.ERROR, instalada, "", null,
                    "Encontre el proyecto pero no pude leer sus versiones para 1.20.1 Forge.");
        }

        boolean hayNueva = RangoVersion.comparar(
                limpiar(ultima.versionNumero()), limpiar(porHash.versionNumero())) > 0;

        if (!hayNueva) {
            return new Info(modId, jar.archivo(), Estado.AL_DIA, porHash.versionNumero(),
                    ultima.versionNumero(), ultima,
                    "Tenes la ultima version disponible para 1.20.1 Forge.");
        }
        return new Info(modId, jar.archivo(), Estado.HAY_ACTUALIZACION, porHash.versionNumero(),
                ultima.versionNumero(), ultima,
                "Actualizar puede romper compatibilidad con otros mods que pidan la version "
                        + "vieja. Faro revisa eso antes de instalar.");
    }

    /** Quita adornos de la version para poder compararla. */
    private static String limpiar(String v) {
        if (v == null) {
            return "";
        }
        String s = v.trim().toLowerCase(Locale.ROOT);
        s = s.replaceAll("^(forge|fabric|neoforge)[-_+]", "");
        s = s.replaceAll("^mc?1\\.20(\\.1)?[-_+]", "");
        s = s.replaceAll("[-_+](forge|fabric)([-_+].*)?$", "");
        s = s.replaceAll("[-_+]mc?1\\.20(\\.1)?$", "");
        return s;
    }

    /**
     * Chequea si actualizar ese mod rompe a otros que ya estan instalados.
     *
     * Es el mismo razonamiento del predictor, aplicado a una actualizacion: subir
     * una libreria de version puede dejar sin cargar a los mods que pedian la
     * anterior. Se avisa ANTES de descargar nada.
     */
    public static List<String> aQuienRompe(Info info, String versionNueva,
                                           List<MetadatosJar> instalados) {
        List<String> afectados = new java.util.ArrayList<>();
        if (versionNueva == null || versionNueva.isBlank()) {
            return afectados;
        }
        for (MetadatosJar j : instalados) {
            for (MetadatosJar.Dependencia d : j.dependencias()) {
                if (!d.obligatoria() || d.esIncompatible()) {
                    continue;
                }
                if (!d.modId().equalsIgnoreCase(info.modId())) {
                    continue;
                }
                if (!d.rango().acepta(limpiar(versionNueva))) {
                    afectados.add(j.nombreVisible() + " (pide " + d.rango().original() + ")");
                }
            }
        }
        return afectados;
    }

    public static String veredicto() {
        if (CACHE.isEmpty()) {
            return "Sin consultar todavia. Tocá 'Buscar actualizaciones' o el boton de un mod "
                    + "concreto en la pestaña Mods.";
        }
        int nuevas = conActualizacion();
        long desconocidos = CACHE.values().stream()
                .filter(i -> i.estado() == Estado.DESCONOCIDO).count();

        if (nuevas == 0) {
            return CACHE.size() + " mods consultados, todos al dia"
                    + (desconocidos > 0 ? " (" + desconocidos + " no estan en Modrinth)." : ".");
        }
        return nuevas + (nuevas == 1 ? " mod tiene" : " mods tienen") + " version nueva"
                + (desconocidos > 0 ? ". " + desconocidos + " no estan en Modrinth y no se pueden verificar." : ".")
                + " Actualizá de a uno y probá el juego entre medio.";
    }

    static {
        Faro.LOG.debug("[Faro] Actualizador listo.");
    }
}
