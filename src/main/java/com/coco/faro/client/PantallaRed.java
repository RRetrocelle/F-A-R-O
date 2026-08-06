package com.coco.faro.client;

import com.coco.faro.config.ConfigFaro;
import com.coco.faro.diag.MonitorRed;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * Red: trafico por mod y tamano de los paquetes NBT.
 *
 * Dos mediciones que responden la misma pregunta practica ("¿por que se traba al
 * moverme?") desde angulos distintos. La pantalla arranca aclarando lo que no
 * puede hacer —bajar el ping— porque es la expectativa que trae la palabra "red"
 * y es mejor sacarla del medio de entrada.
 */
public class PantallaRed extends PantallaHerramienta {

    public PantallaRed(Screen anterior) {
        super(anterior, "Red y paquetes", "quien manda datos, cuantos, y de que tamano");
    }

    @Override
    protected void botones(int x, int y, int ancho) {
        int tercio = (ancho - 12) / 3;
        boolean midiendo = MonitorRed.midiendoNbt();

        addRenderableWidget(Button.builder(
                        Component.literal(midiendo ? "Dejar de medir NBT" : "Medir tamano de NBT"),
                        b -> {
                            boolean nuevo = !MonitorRed.midiendoNbt();
                            MonitorRed.medirNbt(nuevo);
                            ConfigFaro.INSTANCIA.medirNbt.set(nuevo);
                            ConfigFaro.INSTANCIA.medirNbt.save();
                            refrescar();
                        })
                .bounds(x, y, tercio, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Reiniciar contadores"),
                        b -> {
                            MonitorRed.reiniciar();
                            refrescar();
                        })
                .bounds(x + tercio + 6, y, tercio, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Copiar resumen"),
                        b -> {
                            Minecraft.getInstance().keyboardHandler.setClipboard(resumenTexto());
                            AlertasSonoras.listo();
                        })
                .bounds(x + (tercio + 6) * 2, y, tercio, 20).build());
    }

    @Override
    protected int contenido(GuiGraphics g, int x, int y, int ancho) {
        Minecraft mc = Minecraft.getInstance();

        y = veredicto(g, x, y, ancho, MonitorRed.veredicto(),
                MonitorRed.anormales().isEmpty() ? Paleta.OK : Paleta.ADVERTENCIA);

        // --- Latencia, con la aclaracion de alcance por delante.
        y = seccion(g, x, y, ancho, "Latencia");
        int ping = SuavizadoEntidades.ping();
        if (mc.isLocalServer()) {
            y = Widgets.parrafo(g, this.font,
                    "Estas en un mundo local: no hay red de por medio. Si el juego va lento, "
                            + "el problema es de rendimiento, no de conexion.",
                    x, y, ancho, Paleta.TEXTO_TENUE, 3) + 4;
        } else if (ping >= 0) {
            y = fila(g, x, y, ancho, "Ping actual", ping + " ms",
                    ping > 250 ? Paleta.ERROR : (ping > 120 ? Paleta.ADVERTENCIA : Paleta.OK));
            y = Widgets.parrafo(g, this.font,
                    "Faro no puede bajar el ping y ningun otro mod del cliente tampoco: la "
                            + "latencia la define la ruta de red entre tu PC y el servidor. "
                            + "Lo que si se puede es disimular su efecto visual — mirá el "
                            + "suavizado de entidades en los ajustes — y encontrar al mod que "
                            + "esta inundando la conexion, que es lo de abajo.",
                    x, y + 2, ancho, Paleta.TEXTO_APAGADO, 6) + 6;
        } else {
            y = vacio(g, x, y, ancho, "Sin conexion a un servidor.");
        }

        // --- Trafico por canal.
        y = seccion(g, x, y, ancho, "Trafico de mods por canal");
        List<MonitorRed.Canal> canales = MonitorRed.canales();

        if (canales.isEmpty()) {
            y = vacio(g, x, y, ancho,
                    "Todavia no paso ningun paquete de mod. En un mundo local esto puede "
                            + "quedarse en cero para siempre: sin red, no hay paquetes.");
        } else {
            y = fila(g, x, y, ancho, "Total",
                    MonitorRed.legible(MonitorRed.bytesTotales()) + "  en "
                            + MonitorRed.paquetesTotales() + " paquetes", Paleta.TEXTO);
            y = fila(g, x, y, ancho, "Ritmo",
                    String.format(Locale.ROOT, "%.1f KB/s", MonitorRed.bytesPorSegundo() / 1024.0),
                    Paleta.TEXTO_TENUE);
            y = fila(g, x, y, ancho, "Midiendo desde hace",
                    MonitorRed.segundosMidiendo() + " s", Paleta.TEXTO_APAGADO);
            y += 6;

            List<MonitorRed.Canal> raros = MonitorRed.anormales();
            long total = Math.max(1, MonitorRed.bytesTotales());
            long maximo = canales.get(0).bytes();

            int tope = Math.min(canales.size(), 30);
            for (int i = 0; i < tope; i++) {
                MonitorRed.Canal c = canales.get(i);
                boolean anormal = raros.contains(c);
                int color = anormal ? Paleta.ADVERTENCIA : Paleta.NEUTRO;

                String valor = c.bytesLegibles() + "  ("
                        + Math.round(c.bytes() * 100.0 / total) + "%)";
                y = barraDeRanking(g, x, y, ancho, (anormal ? "! " : "") + c.nombre(), valor,
                        c.bytes() / (float) maximo, color);

                Widgets.lineaRecortada(g, this.font,
                        String.format(Locale.ROOT, "   %d paquetes  ·  %.0f bytes de promedio",
                                c.paquetes(), c.promedioBytes()),
                        x, y, ancho, Paleta.TEXTO_APAGADO);
                y += 12;
            }

            if (!raros.isEmpty()) {
                y += 4;
                y = Widgets.parrafo(g, this.font,
                        "Los marcados con ! se salen de lo normal: o se llevan mas del 40% del "
                                + "trafico teniendo competencia, o pasan de 8 KB/s sostenidos. "
                                + "El umbral es relativo a proposito: un pack chico y uno de 190 "
                                + "mods mueven volumenes distintos y los dos pueden estar sanos.",
                        x, y, ancho, Paleta.ADVERTENCIA, 6) + 6;
            }

            y = Widgets.parrafo(g, this.font,
                    "Solo se cuenta lo que los mods mandan por su propio canal. Los paquetes "
                            + "vanilla (movimiento, bloques, chunks) no son de ningun mod en "
                            + "particular y no aparecen aca, aunque un mod puede hacer que haya "
                            + "mas de esos. Es una limitacion real del metodo.",
                    x, y, ancho, Paleta.TEXTO_APAGADO, 6) + 8;
        }

        // --- NBT
        y = seccion(g, x, y, ancho, "Tamano de los datos NBT");
        if (!MonitorRed.midiendoNbt()) {
            y = Widgets.parrafo(g, this.font,
                    "Apagado. Un NBT gigante es la causa clasica del tiron al abrir un cofre o "
                            + "al acercarse a una maquina, y no deja ni una linea en el log.\n\n"
                            + "Viene apagado porque el enganche vive en el buffer por el que pasa "
                            + "TODO el trafico del protocolo: aunque cada medicion sea barata, se "
                            + "ejecuta miles de veces por segundo. Prendelo con el boton de abajo, "
                            + "reproducí el tiron, y apagalo.",
                    x, y, ancho, Paleta.TEXTO_TENUE, 10);
            return y + 6;
        }

        y = fila(g, x, y, ancho, "NBTs medidos", String.valueOf(MonitorRed.nbtCantidad()),
                Paleta.TEXTO);
        y = fila(g, x, y, ancho, "Total", MonitorRed.legible(MonitorRed.nbtBytes()), Paleta.TEXTO);
        y = fila(g, x, y, ancho, "Promedio",
                String.format(Locale.ROOT, "%.0f bytes", MonitorRed.nbtPromedioBytes()),
                Paleta.TEXTO_TENUE);
        y = fila(g, x, y, ancho, "El mayor",
                MonitorRed.legible(MonitorRed.nbtMayorBytes()),
                MonitorRed.nbtMayorBytes() >= MonitorRed.NBT_GRANDE_BYTES
                        ? Paleta.ADVERTENCIA : Paleta.OK);
        y += 6;

        List<MonitorRed.NbtGrande> grandes = MonitorRed.nbtGrandes();
        if (grandes.isEmpty()) {
            y = vacio(g, x, y, ancho,
                    "Ningun NBT paso de " + (MonitorRed.NBT_GRANDE_BYTES / 1024)
                            + " KB. Los tirones que sentis no vienen de datos gigantes por la red.");
        } else {
            y = seccion(g, x, y, ancho, "Los mas grandes (" + grandes.size() + ")");
            for (MonitorRed.NbtGrande n : grandes) {
                if (y > yContenido + altoVisible + 10) {
                    y += 21;
                    continue;
                }
                int color = n.bytes() >= 64 * 1024 ? Paleta.ERROR : Paleta.ADVERTENCIA;
                y = fila(g, x, y, ancho, MonitorRed.legible(n.bytes()),
                        hora(n.momento()), color);
                Widgets.lineaRecortada(g, this.font, "   " + n.contexto(), x, y, ancho,
                        Paleta.TEXTO_APAGADO);
                y += 12;
            }
            y = Widgets.parrafo(g, this.font,
                    "Las claves que ves son las de primer nivel del NBT. Suelen delatar al mod "
                            + "responsable: 'BlockEntityTag' es un contenedor, y un nombre con "
                            + "prefijo de mod es directamente ese mod.",
                    x, y + 4, ancho, Paleta.TEXTO_APAGADO, 5);
        }
        return y + 8;
    }

    private static String hora(long momento) {
        return java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(momento),
                java.time.ZoneId.systemDefault()).withNano(0).toString();
    }

    private String resumenTexto() {
        StringBuilder sb = new StringBuilder("=== Faro — red ===\n");
        sb.append(MonitorRed.veredicto()).append("\n\n");
        sb.append("Trafico por canal:\n");
        for (MonitorRed.Canal c : MonitorRed.canales()) {
            sb.append(String.format(Locale.ROOT, "  %-32s %10s  %6d paquetes%n",
                    c.nombre(), c.bytesLegibles(), c.paquetes()));
        }
        if (MonitorRed.midiendoNbt()) {
            sb.append("\nNBT: ").append(MonitorRed.nbtCantidad()).append(" medidos, mayor ")
                    .append(MonitorRed.legible(MonitorRed.nbtMayorBytes())).append('\n');
            for (MonitorRed.NbtGrande n : MonitorRed.nbtGrandes()) {
                sb.append("  ").append(MonitorRed.legible(n.bytes())).append("  ")
                        .append(n.contexto()).append('\n');
            }
        }
        return sb.toString();
    }
}
