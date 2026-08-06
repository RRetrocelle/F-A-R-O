package com.coco.faro.diag;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Datos conocidos sobre las librerias que mas problemas dan en Forge 1.20.1.
 *
 * Existe para resolver los casos frecuentes sin gastar una consulta de IA ni
 * hacer que el usuario busque a mano. Es una lista corta y curada, no una base
 * exhaustiva: solo entra lo que se repite de verdad en packs de 1.20.1.
 *
 * Cuando un modId no esta aca, no pasa nada: el flujo sigue igual, sin el
 * comentario extra.
 */
public final class BaseFixesLocales {

    /** Consejo puntual sobre una libreria. */
    public record Nota(String nombreLindo, String versionRecomendada, String advertencia) {
    }

    private static final Map<String, Nota> NOTAS = new LinkedHashMap<>();

    static {
        NOTAS.put("geckolib", new Nota("GeckoLib", "4.4.x para 1.20.1",
                "Es la libreria de animaciones que usan casi todos los mods de criaturas. "
                        + "Ojo: la rama 4.5+ es para 1.21. Si instalas la equivocada, todos los "
                        + "mods de mobs dejan de cargar a la vez."));

        NOTAS.put("curios", new Nota("Curios API", "5.14.x+1.20.1",
                "Maneja los slots de accesorios. Los mods que agregan anillos, capas o "
                        + "amuletos dependen de ella."));

        NOTAS.put("kotlinforforge", new Nota("Kotlin For Forge", "4.11.x o 4.12.x",
                "Viene empaquetada dentro de su propio jar (JarInJar). Si ya tenes el archivo "
                        + "kotlinforforge-*-all.jar, la dependencia esta cubierta aunque no la "
                        + "veas suelta en la carpeta."));

        NOTAS.put("architectury", new Nota("Architectury API", "9.2.x para Forge 1.20.1",
                "Capa de compatibilidad multiplataforma. Tiene builds distintas para Forge y "
                        + "Fabric: la de Fabric no sirve aca."));

        NOTAS.put("cloth_config", new Nota("Cloth Config", "11.1.x-forge",
                "Menu de configuracion. Igual que Architectury, hay que usar la build de Forge."));

        NOTAS.put("puzzleslib", new Nota("Puzzles Lib", "8.1.x para 1.20.1",
                "Trae puzzlesaccessapi adentro (JarInJar), asi que esa dependencia no hay que "
                        + "instalarla por separado."));

        NOTAS.put("expandability", new Nota("ExpandAbility", "9.0.x para 1.20.1",
                "Libreria chica de eventos. La piden Artifacts y varios mods de equipamiento."));

        NOTAS.put("resourcefullib", new Nota("Resourceful Lib", "2.1.x para 1.20.1",
                "La usan los mods de Team Resourceful. Suele venir junto con Resourceful Config."));

        NOTAS.put("terrablender", new Nota("TerraBlender", "3.0.1.x para 1.20.1",
                "Reparte los biomas de los mods en el mundo. Es muy sensible a la version: "
                        + "una distinta rompe la generacion del terreno."));

        NOTAS.put("balm", new Nota("Balm", "7.3.x-forge",
                "Capa de compatibilidad de los mods de BlayTheNinth (Waystones, entre otros)."));

        NOTAS.put("create", new Nota("Create", "6.0.8 en 1.20.1",
                "La rama 1.20.1 quedo en 6.0.x. Muchos addons viejos declaran un rango abierto "
                        + "tipo [0.5.1,) que ACEPTA la 6.0.8 aunque nunca la hayan probado: "
                        + "Forge los carga igual y revientan al usarlos."));

        NOTAS.put("flywheel", new Nota("Flywheel", "integrado en Create 6.0+",
                "Desde Create 6.0 no hace falta instalarlo por separado: viene adentro."));

        NOTAS.put("moonlight", new Nota("Moonlight Lib", "2.16.x para 1.20.1",
                "La usan Supplementaries y compania."));

        NOTAS.put("collective", new Nota("Collective", "8.x para 1.20.1",
                "Libreria de Serilum. Casi todos sus mods chicos la piden."));

        NOTAS.put("yungsapi", new Nota("YUNG's API", "4.0.x Forge 1.20",
                "Necesaria para las estructuras de YUNG (Better End Island, Better "
                        + "Nether Fortresses, etc.)."));
    }

    private BaseFixesLocales() {
    }

    public static Nota buscar(String modId) {
        return modId == null ? null : NOTAS.get(modId.toLowerCase(Locale.ROOT));
    }

    public static boolean conoce(String modId) {
        return buscar(modId) != null;
    }

    public static int cantidad() {
        return NOTAS.size();
    }
}
