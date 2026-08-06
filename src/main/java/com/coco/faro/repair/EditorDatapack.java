package com.coco.faro.repair;

import com.coco.faro.Faro;
import com.coco.faro.diag.EscanerRecetas;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Escribe el datapack que resuelve las colisiones de recetas.
 *
 * Como se apaga una receta sin borrar nada del jar del mod: un datapack cargado
 * despues piso el archivo de la receta con uno que trae una condicion de Forge
 * que nunca se cumple ({@code forge:false}). Forge evalua esas condiciones al
 * cargar el data y descarta la receta entera. El mod queda intacto: si borras el
 * datapack, la receta vuelve.
 *
 * Donde se escribe y por que ahi:
 *   {@code saves/<mundo>/datapacks/faro_recetas/}
 * Es la unica carpeta que un mod de cliente puede tocar con garantia de que el
 * juego la va a leer sin pedirle nada al usuario. Los datapacks nuevos de esa
 * carpeta quedan activos al cargar el mundo.
 *
 * Limitacion que la pantalla dice de frente: en un servidor las recetas las
 * define el servidor. Un datapack local no cambia lo que el servidor te manda,
 * asi que ahi esto no aplica y el boton se apaga.
 */
public final class EditorDatapack {

    public static final String NOMBRE_PACK = "faro_recetas";

    /** Version del formato de datapack de 1.20.1. */
    private static final int PACK_FORMAT = 15;

    public record Resultado(boolean exito, String mensaje, Path carpeta, int recetasApagadas) {
    }

    private final RegistroAcciones registro;

    public EditorDatapack(RegistroAcciones registro) {
        this.registro = registro;
    }

    /**
     * Carpeta del datapack del mundo abierto, o null si no hay mundo local.
     *
     * En un servidor remoto no existe una carpeta de mundo del lado del cliente,
     * y ese es exactamente el caso donde esto no puede funcionar.
     */
    public static Path carpetaDatapack() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getSingleplayerServer() == null) {
                return null;
            }
            Path mundo = mc.getSingleplayerServer().getWorldPath(
                    net.minecraft.world.level.storage.LevelResource.ROOT);
            return mundo.resolve("datapacks").resolve(NOMBRE_PACK);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean existePack() {
        Path p = carpetaDatapack();
        return p != null && Files.isDirectory(p);
    }

    /** Recetas ya apagadas por un uso anterior, para no perder lo elegido. */
    public static Set<String> recetasApagadas() {
        Set<String> out = new LinkedHashSet<>();
        Path base = carpetaDatapack();
        if (base == null || !Files.isDirectory(base)) {
            return out;
        }
        Path data = base.resolve("data");
        if (!Files.isDirectory(data)) {
            return out;
        }
        try (var espacios = Files.list(data)) {
            for (Path ns : espacios.toList()) {
                Path recetas = ns.resolve("recipes");
                if (!Files.isDirectory(recetas)) {
                    continue;
                }
                try (var archivos = Files.walk(recetas)) {
                    for (Path a : archivos.filter(Files::isRegularFile).toList()) {
                        String rel = recetas.relativize(a).toString()
                                .replace('\\', '/');
                        if (rel.endsWith(".json")) {
                            out.add(ns.getFileName() + ":" + rel.substring(0, rel.length() - 5));
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * Apaga las recetas indicadas y deja activas las demas.
     *
     * Recibe la lista COMPLETA de lo que debe quedar apagado, no un delta: asi
     * volver a habilitar algo es simplemente no incluirlo, y el estado del disco
     * siempre refleja exactamente lo que se ve en pantalla.
     */
    public Resultado aplicar(List<EscanerRecetas.Entrada> aApagar) {
        Path base = carpetaDatapack();
        if (base == null) {
            return new Resultado(false,
                    "Esto solo funciona en un mundo local. En un servidor las recetas las define "
                            + "el servidor y un datapack tuyo no las cambia.", null, 0);
        }

        try {
            // Se borra y se rehace: el datapack es 100% generado por Faro, no hay
            // nada del usuario adentro que se pueda perder.
            if (Files.isDirectory(base)) {
                borrarRecursivo(base);
            }
            Files.createDirectories(base);

            Files.writeString(base.resolve("pack.mcmeta"), """
                    {
                      "pack": {
                        "pack_format": %d,
                        "description": "Faro — recetas desactivadas a mano. Borra esta carpeta para revertir todo."
                      }
                    }
                    """.formatted(PACK_FORMAT), StandardCharsets.UTF_8);

            Files.writeString(base.resolve("LEEME.txt"), """
                    Este datapack lo genero Faro.

                    Cada archivo .json de data/<mod>/recipes/ pisa una receta del juego con una
                    condicion de Forge que nunca se cumple. Efecto: esa receta deja de existir,
                    sin tocar el .jar del mod.

                    Para revertir TODO: borra esta carpeta entera y volve a entrar al mundo.
                    Para revertir una sola: borra su .json.

                    Recetas apagadas en esta version: %d
                    """.formatted(aApagar.size()), StandardCharsets.UTF_8);

            int escritas = 0;
            List<String> anotadas = new ArrayList<>();
            for (EscanerRecetas.Entrada e : aApagar) {
                if (escribirAnulacion(base, e.id())) {
                    escritas++;
                    anotadas.add(e.id().toString());
                }
            }

            registro.anotar("DATAPACK  " + base);
            registro.anotar("          " + escritas + " receta(s) desactivada(s): "
                    + String.join(", ", anotadas));
            registro.anotar("          Para deshacerlo: borra esa carpeta y volve a entrar al mundo.");

            return new Resultado(true,
                    escritas == 0
                            ? "No quedo ninguna receta desactivada. El datapack quedo vacio."
                            : escritas + " receta(s) desactivada(s). Salí del mundo y volvé a entrar "
                              + "para que tome efecto.",
                    base, escritas);

        } catch (Throwable t) {
            Faro.LOG.error("[Faro] No pude escribir el datapack de recetas", t);
            registro.anotar("ERROR  escribiendo el datapack de recetas: " + t.getMessage());
            return new Resultado(false, "No pude escribirlo: " + t.getMessage(), base, 0);
        }
    }

    /**
     * Escribe el .json que anula una receta.
     *
     * El cuerpo tiene que ser una receta valida aunque nunca se vaya a construir:
     * Forge evalua las condiciones antes de deserializar, pero un JSON roto en la
     * carpeta de data hace ruido en el log. Una shapeless vacia es lo mas inocuo
     * que se puede poner.
     */
    private boolean escribirAnulacion(Path base, ResourceLocation id) {
        try {
            Path destino = base.resolve("data").resolve(id.getNamespace())
                    .resolve("recipes").resolve(id.getPath() + ".json");
            Files.createDirectories(destino.getParent());
            Files.writeString(destino, """
                    {
                      "conditions": [ { "type": "forge:false" } ],
                      "type": "minecraft:crafting_shapeless",
                      "ingredients": [ { "item": "minecraft:barrier" } ],
                      "result": { "item": "minecraft:barrier" }
                    }
                    """, StandardCharsets.UTF_8);
            return true;
        } catch (Throwable t) {
            Faro.LOG.warn("[Faro] No pude anular la receta {}: {}", id, t.toString());
            return false;
        }
    }

    /** Elimina el datapack entero: vuelve todo como estaba. */
    public Resultado revertirTodo() {
        Path base = carpetaDatapack();
        if (base == null || !Files.isDirectory(base)) {
            return new Resultado(true, "No habia nada que revertir.", base, 0);
        }
        try {
            borrarRecursivo(base);
            registro.anotar("DATAPACK  eliminado " + base + " — todas las recetas vuelven a estar activas.");
            return new Resultado(true,
                    "Listo. Salí del mundo y volvé a entrar: todas las recetas vuelven.", base, 0);
        } catch (Throwable t) {
            return new Resultado(false, "No pude borrarlo: " + t.getMessage(), base, 0);
        }
    }

    private static void borrarRecursivo(Path raiz) throws Exception {
        try (var flujo = Files.walk(raiz)) {
            for (Path p : flujo.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
