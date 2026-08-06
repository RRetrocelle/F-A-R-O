package com.coco.faro.client;

import com.coco.faro.diag.EscanerRecetas;
import com.coco.faro.diag.MotorDiagnostico;
import com.coco.faro.repair.EditorDatapack;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolutor grafico de crafteos duplicados.
 *
 * Junta las dos mitades del problema: la deteccion (recorrer el registro de
 * recetas y encontrar las que se pisan) y la resolucion (elegir cual gana y
 * escribir el datapack que apaga las demas).
 *
 * Como se apaga una receta sin tocar el jar del mod: un datapack pisa el archivo
 * de la receta con una condicion de Forge que nunca se cumple. La receta deja de
 * existir; el mod queda intacto. Borrar la carpeta del datapack revierte todo.
 *
 * Limite que la pantalla dice de entrada: en un servidor, las recetas las define
 * el servidor. Un datapack local no cambia lo que te manda, y el boton se apaga.
 */
public class PantallaRecetas extends PantallaHerramienta {

    /** Ids de recetas que el usuario eligio apagar. */
    private final Set<String> apagadas = new LinkedHashSet<>();

    private String mensaje = "";
    private boolean verRedundantes = false;

    public PantallaRecetas(Screen anterior) {
        super(anterior, "Recetas duplicadas",
                "cuando dos mods pelean por la misma combinacion de materiales");
        apagadas.addAll(EditorDatapack.recetasApagadas());
    }

    @Override
    protected void botones(int x, int y, int ancho) {
        int tercio = (ancho - 12) / 3;
        boolean enMundoLocal = EditorDatapack.carpetaDatapack() != null;

        addRenderableWidget(Button.builder(Component.literal("Escanear recetas"),
                        b -> {
                            EscanerRecetas.escanear();
                            refrescar();
                        })
                .bounds(x, y, tercio, 20).build());

        Button aplicar = Button.builder(
                        Component.literal("Aplicar (" + apagadas.size() + " apagadas)"),
                        b -> aplicar())
                .bounds(x + tercio + 6, y, tercio, 20).build();
        aplicar.active = enMundoLocal;
        addRenderableWidget(aplicar);

        Button revertir = Button.builder(Component.literal("Revertir todo"), b -> revertir())
                .bounds(x + (tercio + 6) * 2, y, tercio, 20).build();
        revertir.active = enMundoLocal && EditorDatapack.existePack();
        addRenderableWidget(revertir);
    }

    private void aplicar() {
        MotorDiagnostico motor = MotorDiagnostico.get();
        if (motor == null) {
            return;
        }
        EscanerRecetas.Reporte r = EscanerRecetas.ultimo();
        List<EscanerRecetas.Entrada> lista = new ArrayList<>();
        for (EscanerRecetas.Grupo grupo : r.colisiones()) {
            for (EscanerRecetas.Entrada e : grupo.recetas()) {
                if (apagadas.contains(e.id().toString())) {
                    lista.add(e);
                }
            }
        }
        EditorDatapack.Resultado res = new EditorDatapack(motor.registro()).aplicar(lista);
        mensaje = res.mensaje();
        if (res.exito()) {
            AlertasSonoras.listo();
        } else {
            AlertasSonoras.fallo();
        }
        refrescar();
    }

    private void revertir() {
        MotorDiagnostico motor = MotorDiagnostico.get();
        if (motor == null) {
            return;
        }
        EditorDatapack.Resultado res = new EditorDatapack(motor.registro()).revertirTodo();
        mensaje = res.mensaje();
        apagadas.clear();
        AlertasSonoras.listo();
        refrescar();
    }

    @Override
    protected int contenido(GuiGraphics g, int x, int y, int ancho) {
        if (!mensaje.isEmpty()) {
            y = veredicto(g, x, y, ancho, mensaje, Paleta.NEUTRO);
        }

        EscanerRecetas.Reporte r = EscanerRecetas.ultimo();
        if (!r.hayDatos()) {
            y = veredicto(g, x, y, ancho, r.motivoSinDatos(), Paleta.TEXTO_APAGADO);
            return explicacion(g, x, y, ancho);
        }

        // --- Resumen
        String resumen = r.colisiones().isEmpty()
                ? r.recetasTotales() + " recetas revisadas. Ninguna colision: no hay dos recetas "
                  + "distintas peleando por los mismos materiales."
                : r.colisiones().size() + " colisiones sobre " + r.recetasTotales()
                  + " recetas. Son casos donde los mismos materiales pueden dar cosas distintas "
                  + "y cual gana lo decide el orden interno del registro, que no es predecible.";
        y = veredicto(g, x, y, ancho, resumen,
                r.colisiones().isEmpty() ? Paleta.OK : Paleta.ADVERTENCIA);

        y = fila(g, x, y, ancho, "Recetas en el registro", String.valueOf(r.recetasTotales()),
                Paleta.TEXTO_TENUE);
        y = fila(g, x, y, ancho, "Colisiones (mismos materiales, distinto resultado)",
                String.valueOf(r.colisiones().size()),
                r.colisiones().isEmpty() ? Paleta.OK : Paleta.ADVERTENCIA);
        y = fila(g, x, y, ancho, "Redundantes (identicas, no molestan)",
                String.valueOf(r.redundantes().size()), Paleta.TEXTO_APAGADO);
        y = fila(g, x, y, ancho, "Duro", r.duracionMs() + " ms", Paleta.TEXTO_APAGADO);
        y += 8;

        if (EditorDatapack.carpetaDatapack() == null) {
            y = Widgets.parrafo(g, this.font,
                    "Estas en un servidor (o fuera de un mundo). Las recetas las define el "
                            + "servidor y un datapack tuyo no las cambia, asi que aca solo podes "
                            + "ver el diagnostico. Para resolverlo hay que aplicarlo del lado del "
                            + "servidor.",
                    x, y, ancho, Paleta.ADVERTENCIA, 6) + 6;
        }

        // --- Colisiones
        if (!r.colisiones().isEmpty()) {
            y = seccion(g, x, y, ancho, "Colisiones — elegí cual gana");
            for (EscanerRecetas.Grupo grupo : r.colisiones()) {
                y = dibujarGrupo(g, x, y, ancho, grupo);
            }
        }

        // --- Redundantes
        y += 6;
        boton(g, x, y, verRedundantes
                        ? "Ocultar las redundantes"
                        : "Ver las " + r.redundantes().size() + " redundantes",
                Paleta.TEXTO_TENUE, () -> verRedundantes = !verRedundantes);
        y += 20;

        if (verRedundantes) {
            y = Widgets.parrafo(g, this.font,
                    "Estas son recetas declaradas dos veces con el MISMO resultado. No rompen "
                            + "nada: da igual cual gane porque dan lo mismo. Se listan solo para "
                            + "que sepas que estan.",
                    x, y, ancho, Paleta.TEXTO_APAGADO, 5) + 4;

            int tope = Math.min(r.redundantes().size(), 40);
            for (int i = 0; i < tope; i++) {
                EscanerRecetas.Grupo grupo = r.redundantes().get(i);
                if (y > yContenido + altoVisible + 10) {
                    y += 20;
                    continue;
                }
                Widgets.lineaRecortada(g, this.font,
                        grupo.resultados().get(0) + "  —  " + String.join(", ", grupo.mods()),
                        x + 4, y, ancho - 8, Paleta.TEXTO_TENUE);
                y += 10;
                Widgets.lineaRecortada(g, this.font, "   " + grupo.recetas().size() + " recetas identicas",
                        x + 4, y, ancho - 8, Paleta.TEXTO_APAGADO);
                y += 12;
            }
        }

        return explicacion(g, x, y + 6, ancho);
    }

    private int dibujarGrupo(GuiGraphics g, int x, int y, int ancho, EscanerRecetas.Grupo grupo) {
        int altoEstimado = 26 + grupo.recetas().size() * 22;
        if (y < yContenido - altoEstimado || y > yContenido + altoVisible + 10) {
            return y + altoEstimado;
        }

        Widgets.tarjeta(g, x, y, ancho, 24, Paleta.ADVERTENCIA);
        Widgets.lineaRecortada(g, this.font,
                grupo.recetas().size() + " recetas dan cosas distintas con lo mismo",
                x + 7, y + 4, ancho - 14, Paleta.ADVERTENCIA);
        Widgets.lineaRecortada(g, this.font,
                "compiten: " + String.join("  vs  ", grupo.resultados()),
                x + 7, y + 14, ancho - 14, Paleta.TEXTO);
        y += 28;

        for (EscanerRecetas.Entrada e : grupo.recetas()) {
            String id = e.id().toString();
            boolean apagada = apagadas.contains(id);

            int color = apagada ? Paleta.TEXTO_APAGADO : Paleta.OK;
            String estado = apagada ? "APAGADA" : "activa";

            g.fill(x + 4, y, x + 6, y + 9, color);
            Widgets.lineaRecortada(g, this.font,
                    e.cantidadResultado() + "x " + e.resultado(),
                    x + 10, y, ancho - 70, apagada ? Paleta.TEXTO_APAGADO : Paleta.TEXTO);
            g.drawString(this.font, estado, x + ancho - this.font.width(estado) - 4, y, color, false);
            y += 10;

            Widgets.lineaRecortada(g, this.font, "   " + e.tipo() + "  ·  " + id,
                    x + 10, y, ancho - 20, Paleta.TEXTO_APAGADO);
            y += 11;

            boton(g, x + 10, y, apagada ? "Volver a activarla" : "Apagar esta",
                    apagada ? Paleta.OK : Paleta.ADVERTENCIA,
                    () -> {
                        if (!apagadas.remove(id)) {
                            apagadas.add(id);
                        }
                        refrescar();
                    });
            y += 19;
        }

        // Aviso si apago todas: quedarse sin ninguna receta es peor que la colision.
        long activas = grupo.recetas().stream()
                .filter(e -> !apagadas.contains(e.id().toString())).count();
        if (activas == 0) {
            y = Widgets.parrafo(g, this.font,
                    "! Apagaste todas. Con estos materiales no vas a poder craftear nada. "
                            + "Dejá al menos una activa.",
                    x + 8, y, ancho - 12, Paleta.ERROR, 3);
        }
        return y + 6;
    }

    private int explicacion(GuiGraphics g, int x, int y, int ancho) {
        y = seccion(g, x, y, ancho, "Que se detecta, y que no");
        y = Widgets.parrafo(g, this.font,
                "Se comparan las recetas por su firma exacta de entrada: los ingredientes, y en "
                        + "las con forma tambien la posicion. Los tags se expanden a los items "
                        + "concretos que aceptan, porque dos mods pueden pedir 'cualquier tabla' "
                        + "con tags distintos que apuntan a lo mismo.\n\n"
                        + "Lo que NO se hace: analizar solapamientos parciales. Dos recetas cuyos "
                        + "tags se cruzan a medias pueden chocar sin tener la misma firma, y "
                        + "detectarlo bien exigiria probar todas las combinaciones posibles de "
                        + "cada tag — carisimo con 190 mods, y lleno de falsos positivos. Se "
                        + "compara por firma exacta, que es certeza.\n\n"
                        + "Tampoco entran los tipos de receta propios de cada mod (las maquinas "
                        + "de Create, Mekanism y compania). Sus reglas de entrada no se pueden "
                        + "leer de forma generica, y adivinarlas seria peor que omitirlas.",
                x, y, ancho, Paleta.TEXTO_TENUE, 22) + 6;

        y = seccion(g, x, y, ancho, "Como se aplica");
        y = Widgets.parrafo(g, this.font,
                "Faro escribe un datapack en la carpeta del mundo que pisa cada receta apagada "
                        + "con una condicion de Forge que nunca se cumple. El .jar del mod no se "
                        + "toca: si borras la carpeta del datapack, todo vuelve.\n\n"
                        + "Despues de aplicar hay que salir del mundo y volver a entrar. Los "
                        + "datapacks se leen al cargar.",
                x, y, ancho, Paleta.TEXTO_TENUE, 10);

        java.nio.file.Path carpeta = EditorDatapack.carpetaDatapack();
        if (carpeta != null && java.nio.file.Files.isDirectory(carpeta)) {
            y += 4;
            boton(g, x, y, "Abrir la carpeta del datapack", Paleta.NEUTRO,
                    () -> Util.getPlatform().openFile(carpeta.toFile()));
            y += 20;
        }
        return y;
    }

    /** El escaneo tiene que correr en el hilo del cliente: se dispara al abrir. */
    @Override
    protected void init() {
        super.init();
        if (!EscanerRecetas.ultimo().hayDatos() && Minecraft.getInstance().level != null) {
            EscanerRecetas.escanear();
        }
    }
}
