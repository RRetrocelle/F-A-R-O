package com.coco.faro.diag;

import com.coco.faro.Faro;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Escaneo del Recipe Registry en busca de recetas que se pisan.
 *
 * Que cuenta como "pisarse", y por que la distincion importa:
 *
 *   COLISION  — dos recetas con la MISMA entrada y DISTINTO resultado. Solo una
 *               gana, y cual gana lo decide el orden interno del registro, que no
 *               es estable ni predecible. Este es el problema real: pones los
 *               mismos materiales en la mesa y sale un item que no queriras.
 *
 *   REDUNDANTE— misma entrada y mismo resultado, declarada por dos mods. No
 *               rompe nada; se reporta aparte y en gris, porque es ruido.
 *
 * Lo que NO se hace, a proposito: analisis completo de solapamiento parcial entre
 * ingredientes con tags. Dos recetas cuyos tags se intersectan pueden colisionar
 * sin tener la misma firma, y detectarlo bien exige expandir cada tag y probar
 * todas las combinaciones. Con 190 mods eso es carisimo y daria falsos positivos.
 * Se compara por firma exacta, que es certeza, y la interfaz lo dice.
 *
 * Corre solo en el cliente y solo cuando hay un mundo abierto: antes de eso el
 * RecipeManager esta vacio porque las recetas llegan del servidor.
 */
public final class EscanerRecetas {

    /** Una receta concreta, ya resumida a lo que se puede mostrar. */
    public record Entrada(ResourceLocation id, String modId, String tipo,
                          String firmaEntrada, String resultado, int cantidadResultado) {

        public String nombreCorto() {
            return id.getPath();
        }
    }

    /** Un grupo de recetas que comparten entrada. */
    public record Grupo(String firmaEntrada, List<Entrada> recetas, boolean colision) {

        /** Los modIds involucrados, en orden de aparicion. */
        public List<String> mods() {
            List<String> out = new ArrayList<>();
            for (Entrada e : recetas) {
                if (!out.contains(e.modId())) {
                    out.add(e.modId());
                }
            }
            return out;
        }

        /** Resultados distintos que se disputan esta entrada. */
        public List<String> resultados() {
            List<String> out = new ArrayList<>();
            for (Entrada e : recetas) {
                String r = e.cantidadResultado() + "x " + e.resultado();
                if (!out.contains(r)) {
                    out.add(r);
                }
            }
            return out;
        }
    }

    public record Reporte(List<Grupo> colisiones, List<Grupo> redundantes,
                          int recetasTotales, long duracionMs, String motivoSinDatos) {

        public boolean hayDatos() {
            return motivoSinDatos == null;
        }

        public static Reporte sinDatos(String motivo) {
            return new Reporte(List.of(), List.of(), 0, 0L, motivo);
        }
    }

    private static volatile Reporte ultimo = Reporte.sinDatos(
            "Todavia no se escanearon las recetas. Entra a un mundo y volve a abrir esta pantalla: "
                    + "las recetas las manda el servidor al conectarse, antes de eso la lista esta vacia.");

    private EscanerRecetas() {
    }

    public static Reporte ultimo() {
        return ultimo;
    }

    /**
     * Recorre el registro de recetas cargado y arma el reporte.
     *
     * Es una pasada lineal sobre unas pocas miles de recetas; en la practica
     * tarda decenas de milisegundos. Aun asi se llama desde un hilo aparte y
     * nunca desde el render.
     */
    public static Reporte escanear() {
        long inicio = System.currentTimeMillis();

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            ultimo = Reporte.sinDatos(
                    "No hay ningun mundo abierto. Las recetas llegan del servidor (o del mundo "
                            + "local) al entrar, asi que desde el menu principal no hay nada que leer.");
            return ultimo;
        }

        RecipeManager manejador;
        try {
            manejador = mc.level.getRecipeManager();
        } catch (Throwable t) {
            ultimo = Reporte.sinDatos("No pude acceder al registro de recetas: " + t);
            return ultimo;
        }

        List<Entrada> todas = new ArrayList<>();
        int total = 0;

        for (Recipe<?> receta : manejador.getRecipes()) {
            total++;
            try {
                Entrada e = describir(receta);
                if (e != null) {
                    todas.add(e);
                }
            } catch (Throwable ignored) {
                // Una receta de un mod con un serializer raro no debe tumbar el escaneo.
            }
        }

        Map<String, List<Entrada>> porFirma = new LinkedHashMap<>();
        for (Entrada e : todas) {
            porFirma.computeIfAbsent(e.firmaEntrada(), k -> new ArrayList<>()).add(e);
        }

        List<Grupo> colisiones = new ArrayList<>();
        List<Grupo> redundantes = new ArrayList<>();

        for (Map.Entry<String, List<Entrada>> e : porFirma.entrySet()) {
            List<Entrada> lista = e.getValue();
            if (lista.size() < 2) {
                continue;
            }
            boolean mismoResultado = lista.stream()
                    .allMatch(r -> r.resultado().equals(lista.get(0).resultado())
                            && r.cantidadResultado() == lista.get(0).cantidadResultado());

            Grupo g = new Grupo(e.getKey(), lista, !mismoResultado);
            if (mismoResultado) {
                redundantes.add(g);
            } else {
                colisiones.add(g);
            }
        }

        // Las que involucran mas mods primero: son las mas confusas de resolver a mano.
        colisiones.sort((a, b) -> Integer.compare(b.mods().size(), a.mods().size()));

        long duracion = System.currentTimeMillis() - inicio;
        MonitorHardware.get().registrarTrabajoPropio(duracion * 1_000_000L);

        ultimo = new Reporte(colisiones, redundantes, total, duracion, null);
        Faro.LOG.info("[Faro] Recetas escaneadas: {} totales, {} colisiones, {} redundantes ({} ms)",
                total, colisiones.size(), redundantes.size(), duracion);
        return ultimo;
    }

    /** Lanza el escaneo en segundo plano para no trabar el hilo del juego. */
    public static void escanearEnSegundoPlano() {
        Thread t = new Thread(() -> {
            try {
                // El RecipeManager se lee desde el hilo del cliente: pedimos que
                // la pasada corra ahi y solo esperamos el resultado.
                Minecraft.getInstance().execute(EscanerRecetas::escanear);
            } catch (Throwable e) {
                Faro.LOG.warn("[Faro] Fallo el escaneo de recetas: {}", e.toString());
            }
        }, "Faro-Recetas");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /**
     * Reduce una receta a (firma de entrada, resultado).
     *
     * Solo se manejan los tipos donde "misma entrada" tiene un significado claro:
     * la mesa de crafteo y los hornos. Los tipos propios de cada mod (maquinas de
     * Create, Mekanism, etc.) tienen reglas de entrada que no se pueden leer de
     * forma generica, y adivinar seria peor que omitirlos.
     */
    private static Entrada describir(Recipe<?> receta) {
        ResourceLocation id = receta.getId();
        String modId = id.getNamespace();

        ItemStack salida;
        try {
            salida = receta.getResultItem(
                    Minecraft.getInstance().level.registryAccess());
        } catch (Throwable t) {
            return null;
        }
        if (salida == null || salida.isEmpty()) {
            return null;
        }
        String resultado = clave(salida);
        int cantidad = salida.getCount();

        if (receta instanceof CraftingRecipe cr) {
            String firma = firmaCrafteo(cr);
            return firma == null ? null
                    : new Entrada(id, modId, tipoLegible(cr), firma, resultado, cantidad);
        }
        if (receta instanceof AbstractCookingRecipe ac) {
            List<Ingredient> ing = ac.getIngredients();
            if (ing.isEmpty()) {
                return null;
            }
            String firma = tipoLegible(ac) + "|" + firmaIngrediente(ing.get(0));
            return new Entrada(id, modId, tipoLegible(ac), firma, resultado, cantidad);
        }
        return null;
    }

    /**
     * Firma de una receta de mesa de crafteo.
     *
     * Con forma (shaped) la posicion importa, asi que la firma conserva el orden
     * y las dimensiones. Sin forma (shapeless) da igual donde pongas cada cosa,
     * asi que los ingredientes se ordenan antes de unirlos: si no, la misma
     * receta declarada en otro orden pareceria distinta.
     */
    private static String firmaCrafteo(CraftingRecipe receta) {
        List<Ingredient> ingredientes = receta.getIngredients();
        if (ingredientes.isEmpty()) {
            return null;
        }
        List<String> partes = new ArrayList<>(ingredientes.size());
        for (Ingredient i : ingredientes) {
            partes.add(firmaIngrediente(i));
        }

        if (receta instanceof ShapedRecipe sr) {
            return "shaped" + sr.getWidth() + "x" + sr.getHeight() + "|" + String.join(",", partes);
        }
        Collections.sort(partes);
        return "shapeless|" + String.join(",", partes);
    }

    /**
     * Firma de un ingrediente: los items que acepta, ordenados.
     *
     * Se expande el tag a items concretos a proposito. Dos mods pueden pedir
     * "cualquier tabla" con tags distintos que apuntan a lo mismo, y comparando
     * el nombre del tag esa colision se escaparia.
     */
    private static String firmaIngrediente(Ingredient ing) {
        if (ing == null || ing.isEmpty()) {
            return "-";
        }
        ItemStack[] items;
        try {
            items = ing.getItems();
        } catch (Throwable t) {
            return "?";
        }
        if (items.length == 0) {
            return "-";
        }
        String[] nombres = new String[items.length];
        for (int i = 0; i < items.length; i++) {
            nombres[i] = clave(items[i]);
        }
        Arrays.sort(nombres);
        // Con muchos items (un tag grande) la firma completa seria enorme y no
        // aportaria: se resume por hash conservando el tamano, que ya distingue.
        if (nombres.length > 8) {
            return "set" + nombres.length + ":" + Integer.toHexString(String.join(",", nombres).hashCode());
        }
        return String.join("/", nombres);
    }

    private static String clave(ItemStack pila) {
        try {
            ResourceLocation r = net.minecraftforge.registries.ForgeRegistries.ITEMS
                    .getKey(pila.getItem());
            return r == null ? pila.getItem().toString() : r.toString();
        } catch (Throwable t) {
            return String.valueOf(pila.getItem());
        }
    }

    private static String tipoLegible(Recipe<?> r) {
        try {
            ResourceLocation tipo = net.minecraftforge.registries.ForgeRegistries.RECIPE_TYPES
                    .getKey(r.getType());
            if (tipo == null) {
                return "receta";
            }
            return switch (tipo.toString()) {
                case "minecraft:crafting" -> "mesa";
                case "minecraft:smelting" -> "horno";
                case "minecraft:blasting" -> "fundicion";
                case "minecraft:smoking" -> "ahumador";
                case "minecraft:campfire_cooking" -> "fogata";
                default -> tipo.getPath();
            };
        } catch (Throwable t) {
            return "receta";
        }
    }
}
