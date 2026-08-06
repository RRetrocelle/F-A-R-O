package com.coco.faro.diag;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Cruza dos fuentes que dicen cosas distintas:
 *
 *   - {@link ModList}: lo que Forge LOGRO cargar.
 *   - {@link EscanerJars}: lo que hay FISICAMENTE en la carpeta.
 *
 * La diferencia entre ambas es informacion valiosa: un .jar presente cuyo modId
 * no figura en ModList directamente no cargo, y eso normalmente pasa
 * desapercibido hasta que falta contenido en el mundo.
 *
 * Ya no parsea mods.toml por su cuenta; recibe los metadatos ya leidos por
 * EscanerJars para no abrir 190 archivos zip dos veces.
 */
public final class InventarioMods {

    public record Entrada(String modId, String nombre, String version, Path jar) {
    }

    private final Map<String, Entrada> cargados = new HashMap<>();
    private final List<MetadatosJar> jars;
    private final List<String> jarsQueNoCargaron = new ArrayList<>();
    private final Path carpetaMods;

    private InventarioMods(Path carpetaMods, List<MetadatosJar> jars) {
        this.carpetaMods = carpetaMods;
        this.jars = jars == null ? List.of() : jars;
    }

    public static InventarioMods construir(Path carpetaMods, List<MetadatosJar> jars) {
        InventarioMods inv = new InventarioMods(carpetaMods, jars);
        inv.leerModList();
        inv.compararConCarpeta();
        return inv;
    }

    private void leerModList() {
        for (IModInfo info : ModList.get().getMods()) {
            String id = info.getModId();
            Path jar = null;
            try {
                jar = info.getOwningFile().getFile().getFilePath();
            } catch (Throwable ignored) {
                // Los mods sinteticos de Forge no tienen archivo. No es un error.
            }
            cargados.put(id.toLowerCase(Locale.ROOT), new Entrada(
                    id,
                    info.getDisplayName() == null ? id : info.getDisplayName(),
                    info.getVersion() == null ? "?" : info.getVersion().toString(),
                    jar));
        }
    }

    private void compararConCarpeta() {
        for (MetadatosJar j : jars) {
            if (j.sinMetadatosDeMod() || j.esLibreria()) {
                continue; // libreria o coremod: no se espera que aparezca en ModList
            }
            // Se cuentan tambien los mods anidados: un jar que solo empaqueta
            // otros mods (JarInJar) cargo bien si alguno de esos aparece.
            boolean algunoCargo = j.todosLosModIds().stream()
                    .anyMatch(id -> cargados.containsKey(id.toLowerCase(Locale.ROOT)));
            if (!algunoCargo) {
                jarsQueNoCargaron.add(j.nombreArchivo());
            }
        }
    }

    public Optional<Entrada> porId(String modId) {
        return modId == null ? Optional.empty()
                : Optional.ofNullable(cargados.get(modId.toLowerCase(Locale.ROOT)));
    }

    public int cantidadCargados() {
        return cargados.size();
    }

    public int cantidadJarsEnCarpeta() {
        return jars.size();
    }

    public List<String> jarsQueNoCargaron() {
        return List.copyOf(jarsQueNoCargaron);
    }

    public List<MetadatosJar> jars() {
        return jars;
    }

    public Path carpetaMods() {
        return carpetaMods;
    }

    public Set<String> idsCargados() {
        return new HashSet<>(cargados.keySet());
    }

    /** Suma del tamano de todos los jars, en MB. Da idea del peso del pack. */
    public long tamanoTotalMB() {
        long total = 0L;
        for (MetadatosJar j : jars) {
            total += j.tamano();
        }
        return total / (1024 * 1024);
    }
}
