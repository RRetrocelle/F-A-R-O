package com.coco.faro.repair;

import com.coco.faro.Faro;
import com.coco.faro.net.ClienteModrinth;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

/**
 * Descarga e instala un .jar desde Modrinth.
 *
 * Reglas que se cumplen siempre, sin excepcion:
 *   - Solo se ejecuta despues de que el usuario confirmo ESE archivo puntual.
 *   - Se baja primero a un archivo temporal, nunca directo a mods/.
 *   - Se compara el SHA-1 contra el que declara la API. Si no coincide, se borra
 *     el temporal y no se instala nada. Un hash distinto significa descarga
 *     corrupta o archivo cambiado; en cualquier caso no lo queremos.
 *   - Si ya existe un archivo con ese nombre, no se pisa.
 *   - Todo queda anotado en faro/acciones.log.
 */
public final class InstaladorMods {

    private static final int TIMEOUT_MS = 30000;

    public enum Estado { OK, HASH_INVALIDO, YA_EXISTE, ERROR_RED, ERROR_IO }

    public record Resultado(Estado estado, String mensaje, Path archivo) {
        public boolean exito() {
            return estado == Estado.OK;
        }
    }

    private final RegistroAcciones registro;

    public InstaladorMods(RegistroAcciones registro) {
        this.registro = registro;
    }

    public Resultado instalar(ClienteModrinth.Candidato c, Path carpetaMods) {
        return instalar(c, carpetaMods, false);
    }

    /**
     * @param reemplazar si true, mueve al backup cualquier jar viejo del mismo
     *                   mod antes de instalar el nuevo
     */
    public Resultado instalar(ClienteModrinth.Candidato c, Path carpetaMods, boolean reemplazar) {
        if (c == null || c.url() == null || c.url().isBlank()) {
            return new Resultado(Estado.ERROR_RED, "No hay una URL de descarga valida.", null);
        }

        Path destino = carpetaMods.resolve(c.nombreArchivo());

        if (reemplazar) {
            // Se apartan TODAS las versiones viejas del mismo mod, no solo la de
            // nombre identico: dos jars del mismo mod en la carpeta es un crash
            // garantizado al arrancar.
            int apartados = apartarVersionesViejas(c, carpetaMods);
            if (apartados > 0) {
                registro.anotar("REEMPLAZO  se apartaron " + apartados
                        + " version(es) previa(s) de '" + c.modId() + "'");
            }
        } else if (Files.exists(destino)) {
            return new Resultado(Estado.YA_EXISTE,
                    "Ya existe " + c.nombreArchivo() + " en la carpeta mods. No lo piso.", destino);
        }

        Path temporal = null;
        try {
            Files.createDirectories(carpetaMods);
            temporal = Files.createTempFile("faro-descarga-", ".jar.part");

            long bytes = descargarA(c.url(), temporal);
            registro.anotar("DESCARGA  " + c.nombreArchivo() + "  (" + bytes + " bytes) desde " + c.fuente());

            // Verificacion de integridad antes de tocar la carpeta mods.
            if (c.sha1() != null && !c.sha1().isBlank()) {
                String real = sha1(temporal);
                if (!real.equalsIgnoreCase(c.sha1())) {
                    Files.deleteIfExists(temporal);
                    registro.anotar("RECHAZADO  hash distinto. esperado=" + c.sha1() + " obtenido=" + real);
                    return new Resultado(Estado.HASH_INVALIDO,
                            "El archivo descargado no coincide con el hash oficial. No lo instalo.", null);
                }
            }

            Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING);
            registro.anotar("INSTALADO  " + destino + "  version=" + c.versionNumero()
                    + "  sha1=" + c.sha1());
            registro.anotar("           Para deshacerlo: borra ese archivo de la carpeta mods.");

            return new Resultado(Estado.OK,
                    "Instalado " + c.nombreArchivo() + " (hash verificado).", destino);

        } catch (Throwable t) {
            Faro.LOG.error("[Faro] Fallo la instalacion de {}", c.nombreArchivo(), t);
            try {
                if (temporal != null) {
                    Files.deleteIfExists(temporal);
                }
            } catch (Throwable ignored) {
            }
            registro.anotar("ERROR  instalando " + c.nombreArchivo() + ": " + t.getMessage());
            return new Resultado(Estado.ERROR_RED, "No pude descargarlo: " + t.getMessage(), null);
        }
    }

    /**
     * Mueve al backup los .jar de la carpeta que pertenecen al mismo mod.
     *
     * Se identifican leyendo los modId de cada jar, no comparando nombres de
     * archivo: "curios-forge-5.9.0.jar" y "curios-forge-5.14.1.jar" son el mismo
     * mod aunque el nombre difiera, y dejar los dos rompe el arranque.
     */
    private int apartarVersionesViejas(ClienteModrinth.Candidato c, Path carpetaMods) {
        int movidos = 0;
        String idBuscado = c.modId().toLowerCase();

        for (com.coco.faro.diag.MetadatosJar j
                : com.coco.faro.diag.EscanerJars.escanear(carpetaMods)) {
            boolean esElMismoMod = j.todosLosModIds().stream()
                    .anyMatch(id -> id.equalsIgnoreCase(idBuscado));
            if (!esElMismoMod) {
                continue;
            }
            // Si ya es exactamente el archivo que vamos a instalar, no se toca.
            if (j.nombreArchivo().equalsIgnoreCase(c.nombreArchivo())) {
                continue;
            }
            try {
                Path destinoDir = carpetaMods.resolve(ServicioReparacion.CARPETA_DESTINO);
                Files.createDirectories(destinoDir);
                Path aparte = destinoDir.resolve(j.nombreArchivo());
                int n = 1;
                while (Files.exists(aparte)) {
                    aparte = destinoDir.resolve(j.nombreArchivo() + "." + n++);
                }
                Files.move(j.archivo(), aparte);
                registro.anotarMovimiento(j.archivo(), aparte,
                        "version previa de " + c.modId() + ", reemplazada");
                movidos++;
            } catch (Throwable t) {
                Faro.LOG.warn("[Faro] No pude apartar {}: {}", j.nombreArchivo(), t.toString());
            }
        }
        return movidos;
    }

    private static long descargarA(String url, Path destino) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        try {
            con.setRequestMethod("GET");
            con.setRequestProperty("User-Agent", "faro-modpack-companion/0.3.0 (diagnostico local)");
            con.setConnectTimeout(TIMEOUT_MS);
            con.setReadTimeout(TIMEOUT_MS);
            con.setInstanceFollowRedirects(true);

            int codigo = con.getResponseCode();
            if (codigo != 200) {
                throw new IllegalStateException("el servidor respondio " + codigo);
            }
            long total = 0L;
            try (InputStream in = con.getInputStream();
                 OutputStream out = Files.newOutputStream(destino)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                }
            }
            return total;
        } finally {
            con.disconnect();
        }
    }

    public static String sha1(Path archivo) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(archivo)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
