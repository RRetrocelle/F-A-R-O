package com.coco.faro.repair;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;

/**
 * La unica parte de Faro que escribe en la carpeta mods.
 *
 * Lo que hace de verdad: mover un .jar a una subcarpeta. Nada mas.
 * Lo que NO hace, y conviene tener presente: no parchea mods, no resuelve
 * incompatibilidades, no "repara" un mod roto. Desactivar es la unica accion
 * automatica que se puede tomar con garantias, porque es trivialmente reversible.
 */
public final class ServicioReparacion {

    /**
     * Donde van los .jar desactivados. Es la unica carpeta que Faro escribe
     * dentro de mods/, y funciona como respaldo: mover no destruye nada, y
     * devolver el archivo a mano alcanza para revertir cualquier decision.
     */
    public static final String CARPETA_DESTINO = "faro_backup";

    /**
     * Mods que Faro nunca va a mover por su cuenta, aunque aparezcan en un
     * stacktrace. Son librerias de las que dependen decenas de otros mods:
     * sacarlas causaria una cascada de fallos peor que el problema original.
     */
    private static final Set<String> NUNCA_TOCAR = Set.of(
            "faro", "forge", "minecraft", "kotlinforforge", "architectury",
            "cloth_config", "curios", "geckolib", "puzzleslib", "resourcefullib",
            "moonlight", "collective", "balm", "creativecore", "framework",
            "supplementaries", "sophisticatedcore", "yungsapi", "terrablender",
            "blueprint", "octolib", "elysiumapi", "knightlib", "baguettelib",
            "nirvanalib", "resourcefulconfig", "fzzy_config", "cupboard"
    );

    public enum Estado { OK, PROTEGIDO, NO_EXISTE, ERROR_IO }

    public record Resultado(Estado estado, String mensaje, Path destino) {
        public boolean exito() {
            return estado == Estado.OK;
        }
    }

    private final RegistroAcciones registro;

    public ServicioReparacion(RegistroAcciones registro) {
        this.registro = registro;
    }

    /** true si este mod esta en la lista de intocables. */
    public static boolean esProtegido(String modId) {
        return modId != null && NUNCA_TOCAR.contains(modId.toLowerCase(Locale.ROOT));
    }

    /**
     * Mueve el jar indicado a mods/deshabilitados_por_faro/.
     * No borra nada: si ya existe un archivo con el mismo nombre en destino,
     * le agrega un sufijo en vez de pisarlo.
     */
    public Resultado desactivar(Path jar, String modId, String motivo) {
        if (esProtegido(modId)) {
            String msg = "'" + modId + "' es una libreria de la que dependen otros mods. "
                    + "No la desactivo automaticamente.";
            registro.anotar("RECHAZADO  mod=" + modId + "  motivo=libreria protegida");
            return new Resultado(Estado.PROTEGIDO, msg, null);
        }
        if (jar == null || !Files.isRegularFile(jar)) {
            return new Resultado(Estado.NO_EXISTE, "No encontre el archivo .jar para mover.", null);
        }

        try {
            Path carpetaMods = jar.getParent();
            Path destinoDir = carpetaMods.resolve(CARPETA_DESTINO);
            Files.createDirectories(destinoDir);

            Path destino = destinoDir.resolve(jar.getFileName().toString());
            int n = 1;
            while (Files.exists(destino)) {
                destino = destinoDir.resolve(jar.getFileName() + "." + n);
                n++;
            }

            Files.move(jar, destino, StandardCopyOption.ATOMIC_MOVE);
            registro.anotarMovimiento(jar, destino, motivo);

            escribirNotaEnCarpeta(destinoDir);

            return new Resultado(Estado.OK,
                    "Movi " + jar.getFileName() + " a " + CARPETA_DESTINO + "/", destino);
        } catch (IOException e) {
            registro.anotar("ERROR  al mover " + jar + " : " + e.getMessage());
            return new Resultado(Estado.ERROR_IO,
                    "No pude mover el archivo: " + e.getMessage(), null);
        }
    }

    /** Devuelve un jar desactivado a la carpeta mods. */
    public Resultado reactivar(Path jarDesactivado) {
        if (jarDesactivado == null || !Files.isRegularFile(jarDesactivado)) {
            return new Resultado(Estado.NO_EXISTE, "Ese archivo ya no esta.", null);
        }
        try {
            Path carpetaMods = jarDesactivado.getParent().getParent();
            Path destino = carpetaMods.resolve(jarDesactivado.getFileName().toString());
            Files.move(jarDesactivado, destino, StandardCopyOption.ATOMIC_MOVE);
            registro.anotarMovimiento(jarDesactivado, destino, "reactivado por el usuario");
            return new Resultado(Estado.OK, "Devolvi " + destino.getFileName() + " a mods/", destino);
        } catch (IOException e) {
            return new Resultado(Estado.ERROR_IO, "No pude devolverlo: " + e.getMessage(), null);
        }
    }

    /** Deja un README dentro de la carpeta para que se entienda sin abrir el juego. */
    private void escribirNotaEnCarpeta(Path destinoDir) {
        Path nota = destinoDir.resolve("LEEME.txt");
        if (Files.exists(nota)) {
            return;
        }
        String texto = """
                Esta carpeta la creo el mod Faro. Funciona como respaldo.

                Los .jar que estan aca fueron DESACTIVADOS, no borrados. Minecraft solo
                carga mods que estan sueltos en la carpeta 'mods', asi que mientras esten
                aca adentro no se cargan.

                Para volver a activar cualquiera: movelo de vuelta a la carpeta 'mods'
                (un nivel arriba) y reabri el juego. No hace falta tener Faro instalado
                para hacer esto.

                El detalle de que se movio, cuando y por que esta en:  faro/acciones.log
                """;
        try {
            Files.writeString(nota, texto);
        } catch (IOException ignored) {
        }
    }
}
