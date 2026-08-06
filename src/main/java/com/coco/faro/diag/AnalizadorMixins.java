package com.coco.faro.diag;

import com.coco.faro.Faro;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Visualizador de conflictos de Mixins.
 *
 * Un Mixin es un parche que un mod aplica sobre una clase del juego. Cuando dos
 * mods parchean la MISMA clase, no siempre chocan — pero cuando el juego revienta
 * en esa clase, saber quienes la estaban tocando es la mitad del diagnostico.
 *
 * De donde sale el dato, que es lo que decide su certeza:
 *
 *   1. Cada jar declara sus configs de mixins en el MANIFEST ({@code MixinConfigs})
 *      o en {@code mods.toml} ({@code [[mixins]] config="..."}). Ademas, por
 *      convencion casi universal, el archivo se llama {@code *.mixins.json}.
 *   2. Ese .json lista las CLASES mixin, no sus objetivos.
 *   3. El objetivo real vive en la anotacion {@code @Mixin} de cada clase. Se lee
 *      del bytecode con ASM — que Forge ya trae — sin cargar la clase.
 *
 * O sea: esto NO es una heuristica ni una lista curada. Es exactamente lo que el
 * mod declara que va a parchear, leido del archivo. Por eso se reporta con
 * {@link Certeza#ALTA}.
 *
 * Lo que sigue sin poder afirmarse: que dos mods sobre la misma clase se rompan
 * entre si. Dos {@code @Inject} en metodos distintos conviven perfecto. Por eso
 * la pantalla habla de "clases compartidas", no de "conflictos confirmados", y
 * solo sube el tono cuando ademas hay un crash apuntando ahi.
 */
public final class AnalizadorMixins {

    /** Un mixin concreto: que mod lo trae, que clase es y a que le pega. */
    public record Parche(String modId, String claseMixin, String claseObjetivo, boolean soloCliente) {
    }

    /** Una clase del juego parcheada por uno o mas mods. */
    public record Objetivo(String claseObjetivo, List<Parche> parches) {

        public Set<String> mods() {
            Set<String> s = new LinkedHashSet<>();
            for (Parche p : parches) {
                s.add(p.modId());
            }
            return s;
        }

        public boolean compartido() {
            return mods().size() > 1;
        }

        /** Nombre corto para mostrar: 'net.minecraft.world.level.Level' -> 'Level'. */
        public String nombreCorto() {
            int i = claseObjetivo.lastIndexOf('.');
            return i < 0 ? claseObjetivo : claseObjetivo.substring(i + 1);
        }

        /** Paquete sin la clase, para agrupar visualmente. */
        public String paquete() {
            int i = claseObjetivo.lastIndexOf('.');
            return i < 0 ? "" : claseObjetivo.substring(0, i);
        }
    }

    /** Resultado completo del escaneo. */
    public record Reporte(List<Parche> parches, List<Objetivo> objetivos,
                          List<String> jarsIlegibles, long duracionMs) {

        public List<Objetivo> compartidos() {
            return objetivos.stream().filter(Objetivo::compartido).toList();
        }

        public int cantidadMods() {
            Set<String> s = new LinkedHashSet<>();
            for (Parche p : parches) {
                s.add(p.modId());
            }
            return s.size();
        }

        /** Cuantos mixins aporta cada mod, de mayor a menor. */
        public List<Map.Entry<String, Integer>> ranking(int cuantos) {
            Map<String, Integer> conteo = new LinkedHashMap<>();
            for (Parche p : parches) {
                conteo.merge(p.modId(), 1, Integer::sum);
            }
            return conteo.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(cuantos)
                    .map(e -> (Map.Entry<String, Integer>)
                            new java.util.AbstractMap.SimpleEntry<>(e.getKey(), e.getValue()))
                    .toList();
        }

        public static Reporte vacio() {
            return new Reporte(List.of(), List.of(), List.of(), 0L);
        }
    }

    private AnalizadorMixins() {
    }

    /**
     * Escanea todos los jars y arma el mapa de que mod parchea que clase.
     *
     * Trabajo pesado: abre cada zip y lee bytecode. Se llama una sola vez desde
     * un hilo daemon y el resultado queda cacheado en {@link MotorDiagnostico}.
     */
    public static Reporte analizar(List<MetadatosJar> jars) {
        long inicio = System.currentTimeMillis();

        List<Parche> parches = new ArrayList<>();
        List<String> ilegibles = new ArrayList<>();

        for (MetadatosJar meta : jars) {
            try {
                leerJar(meta, parches);
            } catch (Throwable t) {
                ilegibles.add(meta.nombreArchivo());
            }
        }

        // Agrupacion por clase objetivo.
        Map<String, List<Parche>> porObjetivo = new LinkedHashMap<>();
        for (Parche p : parches) {
            porObjetivo.computeIfAbsent(p.claseObjetivo(), k -> new ArrayList<>()).add(p);
        }

        List<Objetivo> objetivos = new ArrayList<>();
        for (Map.Entry<String, List<Parche>> e : porObjetivo.entrySet()) {
            objetivos.add(new Objetivo(e.getKey(), e.getValue()));
        }

        // Primero los compartidos por mas mods: son los unicos que importan mirar.
        objetivos.sort(Comparator
                .comparingInt((Objetivo o) -> -o.mods().size())
                .thenComparing(Objetivo::claseObjetivo));

        long duracion = System.currentTimeMillis() - inicio;
        MonitorHardware.get().registrarTrabajoPropio(duracion * 1_000_000L);
        return new Reporte(parches, objetivos, ilegibles, duracion);
    }

    private static void leerJar(MetadatosJar meta, List<Parche> salida) throws Exception {
        Path jar = meta.archivo();
        String modId = meta.modIdPrincipal();

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (String config : configsDe(zip)) {
                ZipEntry e = zip.getEntry(config);
                if (e == null) {
                    continue;
                }
                leerConfig(zip, e, modId, salida);
            }
        }
    }

    /**
     * Encuentra los archivos de configuracion de mixins dentro del jar.
     *
     * Se buscan por el nombre convencional en la raiz del zip. Leerlo del
     * MANIFEST seria mas formal, pero muchos mods declaran la config solo en
     * mods.toml y el nombre del archivo es la senal que nunca falta.
     */
    private static Set<String> configsDe(ZipFile zip) {
        Set<String> out = new LinkedHashSet<>();
        var entradas = zip.entries();
        while (entradas.hasMoreElements()) {
            ZipEntry e = entradas.nextElement();
            String nombre = e.getName();
            if (e.isDirectory() || nombre.indexOf('/') >= 0) {
                continue; // solo la raiz: las configs de mixins viven ahi
            }
            String bajo = nombre.toLowerCase(Locale.ROOT);
            if (bajo.endsWith(".mixins.json") || bajo.equals("mixins.json")) {
                out.add(nombre);
            }
        }
        return out;
    }

    private static void leerConfig(ZipFile zip, ZipEntry entrada, String modId,
                                   List<Parche> salida) {
        JsonObject raiz;
        try (InputStream in = zip.getInputStream(entrada);
             InputStreamReader rd = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonElement el = JsonParser.parseReader(rd);
            if (!el.isJsonObject()) {
                return;
            }
            raiz = el.getAsJsonObject();
        } catch (Throwable t) {
            return;
        }

        String paquete = raiz.has("package") && raiz.get("package").isJsonPrimitive()
                ? raiz.get("package").getAsString() : "";
        if (paquete.isBlank()) {
            return;
        }

        agregarLista(zip, raiz, "mixins", paquete, modId, false, salida);
        agregarLista(zip, raiz, "client", paquete, modId, true, salida);
        agregarLista(zip, raiz, "server", paquete, modId, false, salida);
    }

    private static void agregarLista(ZipFile zip, JsonObject raiz, String clave, String paquete,
                                     String modId, boolean soloCliente, List<Parche> salida) {
        if (!raiz.has(clave) || !raiz.get(clave).isJsonArray()) {
            return;
        }
        JsonArray arr = raiz.getAsJsonArray(clave);
        for (JsonElement el : arr) {
            if (!el.isJsonPrimitive()) {
                continue;
            }
            String claseCorta = el.getAsString();
            String claseCompleta = paquete + "." + claseCorta;
            String ruta = claseCompleta.replace('.', '/') + ".class";

            ZipEntry entradaClase = zip.getEntry(ruta);
            if (entradaClase == null) {
                continue;
            }
            for (String objetivo : objetivosDe(zip, entradaClase)) {
                salida.add(new Parche(modId, claseCompleta, objetivo, soloCliente));
            }
        }
    }

    /**
     * Lee la anotacion {@code @Mixin} del bytecode y devuelve sus objetivos.
     *
     * La anotacion admite dos formas y hay que soportar las dos:
     *   - {@code @Mixin(Level.class)}       -> valores de tipo Class, en 'value'
     *   - {@code @Mixin(targets = "a.b.C")} -> nombres de texto, en 'targets'
     *
     * La segunda la usan los mixins contra clases privadas o anonimas, que es
     * justo donde mas suelen chocar dos mods.
     */
    private static Set<String> objetivosDe(ZipFile zip, ZipEntry entrada) {
        Set<String> out = new LinkedHashSet<>();
        try (InputStream in = zip.getInputStream(entrada)) {
            ClassReader lector = new ClassReader(in.readAllBytes());
            lector.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(descriptor)) {
                        return null;
                    }
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitArray(String nombre) {
                            boolean porClase = "value".equals(nombre);
                            boolean porTexto = "targets".equals(nombre);
                            if (!porClase && !porTexto) {
                                return null;
                            }
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public void visit(String n, Object valor) {
                                    if (porClase && valor instanceof Type t) {
                                        out.add(t.getClassName());
                                    } else if (porTexto && valor instanceof String s) {
                                        out.add(s.replace('/', '.'));
                                    }
                                }
                            };
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Throwable t) {
            Faro.LOG.debug("[Faro] No pude leer los objetivos de {}: {}",
                    entrada.getName(), t.toString());
        }
        return out;
    }

    /**
     * Clases parcheadas por varios mods QUE ADEMAS aparecen en un stacktrace.
     *
     * Esta es la unica combinacion que justifica hablar de sospecha: la clase
     * fallo y habia mas de un mod modificandola. Sin el crash de por medio, dos
     * mixins sobre la misma clase es lo normal y no significa nada.
     */
    public static List<Objetivo> sospechososEn(Reporte reporte, List<String> lineasStack) {
        if (reporte == null || lineasStack == null || lineasStack.isEmpty()) {
            return List.of();
        }
        String texto = String.join("\n", lineasStack);
        List<Objetivo> out = new ArrayList<>();
        for (Objetivo o : reporte.compartidos()) {
            if (texto.contains(o.claseObjetivo())) {
                out.add(o);
            }
        }
        return out;
    }
}
