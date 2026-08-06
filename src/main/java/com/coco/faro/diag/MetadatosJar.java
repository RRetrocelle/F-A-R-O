package com.coco.faro.diag;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Lo que sabemos de un .jar leyendo su contenido, sin depender de que Forge lo haya cargado. */
public final class MetadatosJar {

    /** Que loader declara el jar. Detectar esto atrapa el clasico "mod de Fabric en Forge". */
    public enum Loader { FORGE, FABRIC, NEOFORGE, MIXTO, NINGUNO }

    /**
     * Una dependencia declarada en mods.toml.
     *
     * El campo {@code tipo} corresponde a la clave {@code type}, que NeoForge y
     * las versiones nuevas de Forge usan para marcar incompatibilidades
     * ("incompatible" / "discouraged"). En Forge 1.20.1 es poco frecuente: casi
     * todos los mods solo declaran {@code mandatory}. Por eso los conflictos
     * declarados van a ser raros, y eso esta bien — cuando aparecen, son certeza.
     */
    public record Dependencia(String modId, boolean obligatoria, RangoVersion rango,
                              String lado, String tipo) {

        public boolean esIncompatible() {
            return "incompatible".equalsIgnoreCase(tipo) || "discouraged".equalsIgnoreCase(tipo);
        }
    }

    private final Path archivo;
    private final long tamano;
    private final long modificado;
    private final Loader loader;
    private final Set<String> modIds = new LinkedHashSet<>();
    private final List<Dependencia> dependencias = new ArrayList<>();

    /**
     * Mods que este jar trae ADENTRO, en META-INF/jarjar/.
     *
     * Forge permite empaquetar dependencias dentro del jar del mod (JarInJar) y
     * las carga como mods de pleno derecho. Sin mirar aca, PuzzlesLib parece no
     * traer 'puzzlesaccessapi' y Kotlin For Forge parece no traer 'kotlinforforge'
     * — y Faro reportaria dependencias faltantes que en realidad estan presentes.
     */
    private final Set<String> modIdsAnidados = new LinkedHashSet<>();
    private final List<String> jarsAnidados = new ArrayList<>();

    /** Valor de FMLModType en el MANIFEST: MOD, LIBRARY, GAMELIBRARY, LANGPROVIDER. */
    private String tipoFML = "";

    private String version = "";
    private String nombreVisible = "";

    MetadatosJar(Path archivo, long tamano, long modificado, Loader loader) {
        this.archivo = archivo;
        this.tamano = tamano;
        this.modificado = modificado;
        this.loader = loader;
    }

    void agregarModId(String id) {
        if (id != null && !id.isBlank()) {
            modIds.add(id);
        }
    }

    void agregarDependencia(Dependencia d) {
        if (d != null) {
            dependencias.add(d);
        }
    }

    void agregarModIdAnidado(String id) {
        if (id != null && !id.isBlank()) {
            modIdsAnidados.add(id);
        }
    }

    void agregarJarAnidado(String nombre) {
        if (nombre != null && !nombre.isBlank()) {
            jarsAnidados.add(nombre);
        }
    }

    void tipoFML(String t) {
        this.tipoFML = t == null ? "" : t;
    }

    public Set<String> modIdsAnidados() {
        return modIdsAnidados;
    }

    public List<String> jarsAnidados() {
        return jarsAnidados;
    }

    public String tipoFML() {
        return tipoFML;
    }

    /**
     * true si el jar es una libreria declarada en el manifest y no un mod normal.
     * Estos no aparecen en ModList con un modId propio, asi que no tiene sentido
     * acusarlos de "no cargaron".
     */
    public boolean esLibreria() {
        return "LIBRARY".equalsIgnoreCase(tipoFML) || "GAMELIBRARY".equalsIgnoreCase(tipoFML);
    }

    /** Todos los modIds que este archivo aporta al juego: propios + anidados. */
    public Set<String> todosLosModIds() {
        Set<String> todos = new LinkedHashSet<>(modIds);
        todos.addAll(modIdsAnidados);
        return todos;
    }

    void version(String v) {
        this.version = v == null ? "" : v;
    }

    void nombreVisible(String n) {
        this.nombreVisible = n == null ? "" : n;
    }

    public Path archivo() {
        return archivo;
    }

    public String nombreArchivo() {
        return archivo.getFileName().toString();
    }

    public long tamano() {
        return tamano;
    }

    public long modificado() {
        return modificado;
    }

    public Loader loader() {
        return loader;
    }

    public Set<String> modIds() {
        return modIds;
    }

    /** El modId principal (el primero declarado), o el nombre del archivo si no hay ninguno. */
    public String modIdPrincipal() {
        return modIds.isEmpty() ? nombreArchivo() : modIds.iterator().next();
    }

    public List<Dependencia> dependencias() {
        return dependencias;
    }

    public String version() {
        return version;
    }

    public String nombreVisible() {
        return nombreVisible.isEmpty() ? modIdPrincipal() : nombreVisible;
    }

    /** true si el jar no aporta ningun mod (libreria embebida, coremod, etc.). */
    public boolean sinMetadatosDeMod() {
        return modIds.isEmpty();
    }
}
