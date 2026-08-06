package com.coco.faro.diag;

import com.coco.faro.Faro;
import com.coco.faro.repair.RegistroAcciones;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Sincronizador de configuracion cliente-servidor.
 *
 * Que problema resuelve: en un pack jugado entre varios, la mitad de los "a mi me
 * pasa y a vos no" salen de que cada uno tiene la carpeta config distinta. Alguien
 * toco un valor, otro dejo el default, un tercero tiene un archivo de una version
 * vieja del pack. Nadie lo nota porque nadie compara 400 archivos a mano.
 *
 * Alcance real, dicho sin adornos: Faro corre en TU cliente. No puede leer la
 * carpeta config de un servidor remoto — eso requiere un mod del lado del
 * servidor, y no lo hay. Lo que si puede, y es lo que resuelve el problema en la
 * practica:
 *
 *   EXPORTAR  — empaqueta tu config/ en un .zip con un manifiesto (hash de cada
 *               archivo, fecha, cantidad de mods). El que arma el pack o el
 *               dueño del server exporta el suyo y lo pasa.
 *   COMPARAR  — abre un .zip y te dice, archivo por archivo, que falta, que
 *               sobra y que difiere. Sin tocar nada.
 *   APLICAR   — copia del zip solo lo que elegiste, con respaldo previo de lo
 *               que se pisa.
 *
 * O sea: la sincronizacion es real y verificable por hash; lo que es manual es el
 * transporte del archivo. Prefiero eso antes que fingir una conexion al servidor
 * que no existe.
 */
public final class SincronizadorConfig {

    private static final DateTimeFormatter SELLO =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public enum Diferencia {
        IGUAL("igual"),
        DISTINTO("distinto"),
        SOLO_EN_PERFIL("falta en tu instalacion"),
        SOLO_LOCAL("solo tuyo, no esta en el perfil");

        public final String etiqueta;

        Diferencia(String etiqueta) {
            this.etiqueta = etiqueta;
        }
    }

    /** El estado de un archivo concreto al comparar. */
    public record Archivo(String ruta, Diferencia diferencia, long bytesLocal,
                          long bytesPerfil, String modProbable) {
    }

    public record Comparacion(List<Archivo> archivos, String origen, String fechaPerfil,
                              int modsDelPerfil, String error) {

        public boolean valida() {
            return error == null;
        }

        public List<Archivo> por(Diferencia d) {
            return archivos.stream().filter(a -> a.diferencia() == d).toList();
        }

        public int diferentes() {
            return por(Diferencia.DISTINTO).size() + por(Diferencia.SOLO_EN_PERFIL).size();
        }

        public static Comparacion fallo(String motivo) {
            return new Comparacion(List.of(), "", "", 0, motivo);
        }
    }

    private final Path carpetaJuego;
    private final RegistroAcciones registro;

    public SincronizadorConfig(Path carpetaJuego, RegistroAcciones registro) {
        this.carpetaJuego = carpetaJuego;
        this.registro = registro;
    }

    private Path carpetaConfig() {
        return carpetaJuego.resolve("config");
    }

    /** Carpeta donde viven los perfiles exportados e importados. */
    public Path carpetaPerfiles() {
        return carpetaJuego.resolve("faro").resolve("perfiles");
    }

    /** Los .zip de perfil disponibles, del mas nuevo al mas viejo. */
    public List<Path> perfiles() {
        List<Path> out = new ArrayList<>();
        Path carpeta = carpetaPerfiles();
        if (!Files.isDirectory(carpeta)) {
            return out;
        }
        try (var flujo = Files.newDirectoryStream(carpeta, "*.zip")) {
            for (Path p : flujo) {
                out.add(p);
            }
        } catch (Throwable ignored) {
        }
        out.sort(Comparator.comparingLong((Path p) -> {
            try {
                return Files.getLastModifiedTime(p).toMillis();
            } catch (Throwable t) {
                return 0L;
            }
        }).reversed());
        return out;
    }

    // ------------------------------------------------------------ exportar

    /**
     * Empaqueta config/ en un .zip con manifiesto.
     *
     * El manifiesto lleva el hash de cada archivo. Comparar por hash y no por
     * fecha ni tamano es lo que hace que la comparacion sea confiable: dos
     * archivos con el mismo contenido son iguales aunque se hayan escrito en
     * momentos distintos, y eso es exactamente lo que pasa cuando dos personas
     * arrancan el mismo pack.
     */
    public Path exportar() throws Exception {
        Path carpeta = carpetaPerfiles();
        Files.createDirectories(carpeta);

        String nombre = "config-" + LocalDateTime.now().format(SELLO) + ".zip";
        Path destino = carpeta.resolve(nombre);

        Path config = carpetaConfig();
        if (!Files.isDirectory(config)) {
            throw new IllegalStateException("No existe la carpeta config.");
        }

        StringBuilder manifiesto = new StringBuilder();
        manifiesto.append("# Perfil de configuracion exportado por Faro\n");
        manifiesto.append("fecha=").append(LocalDateTime.now()).append("\n");
        MotorDiagnostico motor = MotorDiagnostico.get();
        manifiesto.append("mods=").append(motor == null ? 0
                : motor.inventario().map(InventarioMods::cantidadCargados).orElse(0)).append("\n");
        manifiesto.append("# ruta<TAB>sha1<TAB>bytes\n");

        int archivos = 0;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(destino))) {
            List<Path> lista;
            try (var flujo = Files.walk(config)) {
                lista = flujo.filter(Files::isRegularFile).toList();
            }
            for (Path p : lista) {
                String rel = config.relativize(p).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry("config/" + rel));
                Files.copy(p, zip);
                zip.closeEntry();

                manifiesto.append(rel).append('\t')
                        .append(hash(p)).append('\t')
                        .append(Files.size(p)).append('\n');
                archivos++;
            }
            zip.putNextEntry(new ZipEntry("faro-manifiesto.txt"));
            zip.write(manifiesto.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        registro.anotar("PERFIL  exportado " + destino + " con " + archivos + " archivos.");
        Faro.LOG.info("[Faro] Perfil de config exportado: {} ({} archivos)", destino, archivos);
        return destino;
    }

    // ------------------------------------------------------------ comparar

    /** Abre un perfil y lo compara contra la carpeta config actual. Sin tocar nada. */
    public Comparacion comparar(Path perfil) {
        if (perfil == null || !Files.isRegularFile(perfil)) {
            return Comparacion.fallo("No encuentro ese archivo de perfil.");
        }
        Path config = carpetaConfig();

        Map<String, String> hashesPerfil = new LinkedHashMap<>();
        Map<String, Long> tamanosPerfil = new LinkedHashMap<>();
        String fecha = "";
        int mods = 0;

        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(perfil))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if ("faro-manifiesto.txt".equals(e.getName())) {
                    String texto = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    for (String linea : texto.split("\\R")) {
                        if (linea.startsWith("fecha=")) {
                            fecha = linea.substring(6);
                        } else if (linea.startsWith("mods=")) {
                            try {
                                mods = Integer.parseInt(linea.substring(5).trim());
                            } catch (NumberFormatException ignored) {
                            }
                        } else if (!linea.startsWith("#") && linea.contains("\t")) {
                            String[] partes = linea.split("\t");
                            if (partes.length >= 3) {
                                hashesPerfil.put(partes[0], partes[1]);
                                try {
                                    tamanosPerfil.put(partes[0], Long.parseLong(partes[2]));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            return Comparacion.fallo("No pude leer el .zip: " + t.getMessage());
        }

        if (hashesPerfil.isEmpty()) {
            return Comparacion.fallo(
                    "El .zip no trae manifiesto de Faro. Solo se pueden comparar perfiles "
                            + "exportados con Faro, porque hacen falta los hashes.");
        }

        List<Archivo> salida = new ArrayList<>();
        Set<String> vistos = new LinkedHashSet<>();

        for (Map.Entry<String, String> e : hashesPerfil.entrySet()) {
            String rel = e.getKey();
            vistos.add(rel);
            Path local = config.resolve(rel);
            long bytesPerfil = tamanosPerfil.getOrDefault(rel, 0L);

            if (!Files.isRegularFile(local)) {
                salida.add(new Archivo(rel, Diferencia.SOLO_EN_PERFIL, 0, bytesPerfil,
                        modDeRuta(rel)));
                continue;
            }
            String hashLocal = hash(local);
            long bytesLocal;
            try {
                bytesLocal = Files.size(local);
            } catch (Throwable t) {
                bytesLocal = 0;
            }
            salida.add(new Archivo(rel,
                    hashLocal.equalsIgnoreCase(e.getValue()) ? Diferencia.IGUAL : Diferencia.DISTINTO,
                    bytesLocal, bytesPerfil, modDeRuta(rel)));
        }

        // Lo que tenes vos y el perfil no. No es un error, pero conviene verlo:
        // suele ser un mod que tenes de mas.
        if (Files.isDirectory(config)) {
            try (var flujo = Files.walk(config)) {
                for (Path p : flujo.filter(Files::isRegularFile).toList()) {
                    String rel = config.relativize(p).toString().replace('\\', '/');
                    if (!vistos.contains(rel)) {
                        long bytes;
                        try {
                            bytes = Files.size(p);
                        } catch (Throwable t) {
                            bytes = 0;
                        }
                        salida.add(new Archivo(rel, Diferencia.SOLO_LOCAL, bytes, 0, modDeRuta(rel)));
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // Las diferencias primero: es lo unico que hay que mirar.
        salida.sort(Comparator.comparingInt(a -> switch (a.diferencia()) {
            case DISTINTO -> 0;
            case SOLO_EN_PERFIL -> 1;
            case SOLO_LOCAL -> 2;
            case IGUAL -> 3;
        }));

        return new Comparacion(salida, perfil.getFileName().toString(), fecha, mods, null);
    }

    // ------------------------------------------------------------- aplicar

    /**
     * Copia del perfil los archivos indicados, respaldando lo que se pisa.
     *
     * El respaldo va a faro/perfiles/respaldo-<sello>/ conservando la estructura,
     * asi revertir es copiar la carpeta de vuelta sobre config/.
     */
    public String aplicar(Path perfil, List<String> rutas) {
        if (rutas == null || rutas.isEmpty()) {
            return "No elegiste ningun archivo.";
        }
        Path config = carpetaConfig();
        Path respaldo = carpetaPerfiles()
                .resolve("respaldo-" + LocalDateTime.now().format(SELLO));

        Set<String> aCopiar = new LinkedHashSet<>(rutas);
        int copiados = 0;
        int respaldados = 0;

        try {
            Files.createDirectories(respaldo);

            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(perfil))) {
                ZipEntry e;
                while ((e = zip.getNextEntry()) != null) {
                    if (e.isDirectory() || !e.getName().startsWith("config/")) {
                        continue;
                    }
                    String rel = e.getName().substring("config/".length());
                    if (!aCopiar.contains(rel)) {
                        continue;
                    }

                    Path destino = config.resolve(rel);
                    // Defensa contra rutas con '..' dentro del zip. Un perfil
                    // viene de otra persona: no se confia en su contenido.
                    if (!destino.normalize().startsWith(config.normalize())) {
                        Faro.LOG.warn("[Faro] Ruta sospechosa en el perfil, la salteo: {}", rel);
                        continue;
                    }

                    if (Files.isRegularFile(destino)) {
                        Path copia = respaldo.resolve(rel);
                        Files.createDirectories(copia.getParent());
                        Files.copy(destino, copia, StandardCopyOption.REPLACE_EXISTING);
                        respaldados++;
                    }
                    Files.createDirectories(destino.getParent());
                    try (OutputStream out = Files.newOutputStream(destino)) {
                        copiar(zip, out);
                    }
                    copiados++;
                }
            }
        } catch (Throwable t) {
            Faro.LOG.error("[Faro] Fallo al aplicar el perfil", t);
            return "Fallo a mitad de camino: " + t.getMessage()
                    + "  —  lo respaldado quedo en " + respaldo;
        }

        registro.anotar("PERFIL  aplicados " + copiados + " archivos desde " + perfil.getFileName());
        registro.anotar("        respaldo de " + respaldados + " archivos en " + respaldo);
        registro.anotar("        Para revertir: copia esa carpeta de vuelta sobre config/.");

        return copiados + " archivo(s) copiados. " + respaldados + " respaldados en "
                + respaldo.getFileName() + ". Reinicia el juego: los configs se leen al arrancar.";
    }

    private static void copiar(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    private static String hash(Path archivo) {
        try {
            return com.coco.faro.repair.InstaladorMods.sha1(archivo);
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String modDeRuta(String rel) {
        int barra = rel.indexOf('/');
        String primero = barra > 0 ? rel.substring(0, barra) : rel;
        return primero.replaceAll("(?i)[-_](client|server|common)?\\.[a-z0-9]+$", "")
                .replaceAll("\\.[a-z0-9]+$", "")
                .toLowerCase(Locale.ROOT);
    }

    public static String veredicto(Comparacion c) {
        if (c == null || !c.valida()) {
            return c == null ? "Sin comparar." : c.error();
        }
        int distintos = c.por(Diferencia.DISTINTO).size();
        int faltan = c.por(Diferencia.SOLO_EN_PERFIL).size();
        int sobran = c.por(Diferencia.SOLO_LOCAL).size();

        if (distintos == 0 && faltan == 0) {
            return "Tu configuracion coincide con el perfil"
                    + (sobran > 0 ? ", y ademas tenes " + sobran + " archivos propios." : ".");
        }
        return distintos + " archivos distintos y " + faltan + " que no tenes. "
                + "Estas son las diferencias que pueden explicar que a vos te pase algo "
                + "que a los demas no.";
    }
}
