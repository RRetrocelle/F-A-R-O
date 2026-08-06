package com.coco.faro.client;

import com.coco.faro.diag.PerfilCarga;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;
import java.util.Locale;

/**
 * Grafica de carga de mods: cuanto tarda cada uno en inicializarse.
 *
 * Los numeros vienen de cronometrar la entrega de los eventos del ciclo de vida
 * a cada mod, que es medicion directa. La pantalla insiste en lo que queda fuera
 * de esa cuenta, porque si no el usuario compara el total con el tiempo de
 * arranque real y no le cierra — y con razon.
 */
public class PantallaCargaMods extends PantallaHerramienta {

    public PantallaCargaMods(Screen anterior) {
        super(anterior, "Carga de mods", "cuanto tardo cada mod en inicializarse");
    }

    @Override
    protected int altoBotonera() {
        return 4;
    }

    @Override
    protected int contenido(GuiGraphics g, int x, int y, int ancho) {
        if (!PerfilCarga.hayDatos()) {
            return vacio(g, x, y, ancho,
                    "Sin datos.\n\n"
                            + "La medicion se hace con un mixin que cronometra la entrega de los "
                            + "eventos de carga a cada mod. Si no hay nada aca, ese mixin no se "
                            + "aplico en este arranque — puede pasar si otro mod interfiere en "
                            + "esa ruta.\n\n"
                            + "No hay forma de recuperarlo sin reiniciar: los eventos de carga "
                            + "ocurren una sola vez, al principio.");
        }

        y = veredicto(g, x, y, ancho, PerfilCarga.veredicto(), Paleta.NEUTRO);

        // --- Numeros generales
        y = seccion(g, x, y, ancho, "Resumen");
        double arranque = PerfilCarga.arranqueTotalSegundos();
        double medido = PerfilCarga.totalMedidoMs();

        y = fila(g, x, y, ancho, "Arranque completo",
                arranque < 0 ? "sin dato" : String.format(Locale.ROOT, "%.1f s", arranque),
                Paleta.TEXTO);
        y = fila(g, x, y, ancho, "De eso, dentro del codigo de los mods",
                String.format(Locale.ROOT, "%.1f s", medido / 1000.0), Paleta.TEXTO_TITULO);
        y = fila(g, x, y, ancho, "Mods medidos", String.valueOf(PerfilCarga.modsMedidos()),
                Paleta.TEXTO_TENUE);

        if (arranque > 0 && medido > 0) {
            double resto = arranque - medido / 1000.0;
            y = fila(g, x, y, ancho, "El resto (Java, escaneo de archivos, mixins)",
                    String.format(Locale.ROOT, "%.1f s", Math.max(0, resto)), Paleta.TEXTO_APAGADO);
        }
        y += 6;

        y = Widgets.parrafo(g, this.font,
                "Lo que NO entra en la cuenta de los mods: abrir y escanear cada .jar, aplicar "
                        + "los mixins, y todo lo que un mod deja para la primera vez que se usa. "
                        + "Por eso los dos numeros no coinciden, y por eso estan separados en vez "
                        + "de mezclados en un solo porcentaje.",
                x, y, ancho, Paleta.TEXTO_APAGADO, 6) + 8;

        // --- Grafica
        List<PerfilCarga.Medicion> ranking = PerfilCarga.ranking();
        y = seccion(g, x, y, ancho, "Los mas lentos");

        double maximo = ranking.get(0).milisegundos();
        int tope = Math.min(ranking.size(), 60);

        for (int i = 0; i < tope; i++) {
            PerfilCarga.Medicion m = ranking.get(i);
            if (y > yContenido + altoVisible + 10) {
                y += 18;
                continue;
            }
            int color = m.milisegundos() >= 2000 ? Paleta.ERROR
                    : (m.milisegundos() >= 500 ? Paleta.ADVERTENCIA : Paleta.OK);

            String valor = m.milisegundos() >= 1000
                    ? String.format(Locale.ROOT, "%.2f s", m.milisegundos() / 1000.0)
                    : String.format(Locale.ROOT, "%.0f ms", m.milisegundos());

            y = barraDeRanking(g, x, y, ancho, (i + 1) + ". " + m.modId(), valor,
                    (float) (m.milisegundos() / maximo), color);
        }

        if (ranking.size() > tope) {
            g.drawString(this.font, "... y " + (ranking.size() - tope)
                            + " mods mas, todos por debajo de "
                            + String.format(Locale.ROOT, "%.0f ms",
                            ranking.get(tope - 1).milisegundos()),
                    x, y, Paleta.TEXTO_APAGADO, false);
            y += 14;
        }

        y += 6;
        y = seccion(g, x, y, ancho, "Que hacer con esto");
        y = Widgets.parrafo(g, this.font,
                "Un mod lento al cargar no es un mod malo: los que agregan mucho contenido "
                        + "tienen que registrar mucho, y eso lleva tiempo una sola vez.\n\n"
                        + "Sirve para dos cosas concretas. Una: si el arranque se hizo mucho mas "
                        + "largo despues de agregar algo, aca vas a ver cual fue. Dos: si un mod "
                        + "se lleva varios segundos el solo, ahi tenes un candidato claro a "
                        + "revisar su configuracion — muchos permiten desactivar partes que no "
                        + "usas y que igual se cargan.",
                x, y, ancho, Paleta.TEXTO_TENUE, 12);
        return y;
    }
}
