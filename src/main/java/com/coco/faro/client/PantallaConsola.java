package com.coco.faro.client;

import com.coco.faro.diag.MotorDiagnostico;
import com.coco.faro.diag.VigilanteLog;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Consola en vivo de latest.log, coloreada por nivel.
 *
 * Lee del buffer que ya mantiene {@link VigilanteLog}, que va leyendo el archivo
 * de forma incremental en un hilo aparte. La pantalla no toca el disco: solo
 * dibuja lo que ya esta en memoria.
 *
 * El auto-scroll se apaga solo apenas el usuario se desplaza hacia arriba, que es
 * lo que uno espera cuando esta leyendo algo viejo y siguen entrando lineas.
 */
public class PantallaConsola extends Screen {

    private static final int ALTO_LINEA = 9;

    private final Screen anterior;

    private EditBox filtro;
    private int scroll = 0;
    private boolean autoScroll = true;
    private boolean soloProblemas = false;

    private int areaX;
    private int areaY;
    private int areaAncho;
    private int areaAlto;

    public PantallaConsola(Screen anterior) {
        super(Component.literal("Faro — consola"));
        this.anterior = anterior;
    }

    @Override
    protected void init() {
        areaX = 8;
        areaY = 44;
        areaAncho = this.width - 16;
        areaAlto = this.height - areaY - 32;

        filtro = new EditBox(this.font, 8, 22, Math.min(220, this.width - 200), 16,
                Component.literal("filtro"));
        filtro.setHint(Component.literal("filtrar texto..."));
        filtro.setResponder(s -> {
            scroll = 0;
            autoScroll = true;
        });
        addRenderableWidget(filtro);

        int xBoton = filtro.getX() + filtro.getWidth() + 6;

        addRenderableWidget(Button.builder(
                        Component.literal(soloProblemas ? "Solo problemas: si" : "Solo problemas: no"),
                        b -> {
                            soloProblemas = !soloProblemas;
                            this.clearWidgets();
                            init();
                        })
                .bounds(xBoton, 20, 108, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Abrir archivo"), b -> {
                    MotorDiagnostico m = MotorDiagnostico.get();
                    if (m != null) {
                        Util.getPlatform().openFile(m.carpetaLogs().resolve("latest.log").toFile());
                    }
                })
                .bounds(xBoton + 112, 20, 84, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Volver"), b -> onClose())
                .bounds(this.width - 74, this.height - 24, 66, 20).build());
    }

    /** Aplica filtro de texto y de nivel sobre lo que hay en el buffer. */
    private List<VigilanteLog.Evento> visibles() {
        MotorDiagnostico motor = MotorDiagnostico.get();
        if (motor == null) {
            return List.of();
        }
        String texto = filtro == null ? "" : filtro.getValue().trim().toLowerCase(Locale.ROOT);
        List<VigilanteLog.Evento> out = new ArrayList<>();

        for (VigilanteLog.Evento e : motor.vigilante().lineasConsola()) {
            if (soloProblemas && !esProblema(e.nivel())) {
                continue;
            }
            if (!texto.isEmpty()
                    && !e.mensaje().toLowerCase(Locale.ROOT).contains(texto)
                    && !e.origen().toLowerCase(Locale.ROOT).contains(texto)) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    private static boolean esProblema(String nivel) {
        return "ERROR".equals(nivel) || "FATAL".equals(nivel) || "WARN".equals(nivel);
    }

    private static int colorDeNivel(String nivel) {
        return switch (nivel) {
            case "ERROR", "FATAL" -> Paleta.ERROR;
            case "WARN" -> Paleta.ADVERTENCIA;
            case "DEBUG", "TRACE" -> Paleta.VIOLETA;
            default -> Paleta.TEXTO_TENUE;
        };
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);

        g.drawString(this.font, "Consola en vivo  ·  latest.log", 8, 8, Paleta.TEXTO_TITULO, false);

        List<VigilanteLog.Evento> lista = visibles();
        int filasVisibles = Math.max(1, areaAlto / ALTO_LINEA);
        int maxScroll = Math.max(0, lista.size() - filasVisibles);

        if (autoScroll) {
            scroll = maxScroll;
        } else {
            scroll = Math.min(scroll, maxScroll);
        }

        g.fill(areaX, areaY, areaX + areaAncho, areaY + areaAlto, 0xC00A0C10);
        Widgets.borde(g, areaX, areaY, areaAncho, areaAlto, Paleta.BORDE_SUAVE);

        g.enableScissor(areaX, areaY, areaX + areaAncho, areaY + areaAlto);
        int y = areaY + 3;
        for (int i = scroll; i < Math.min(lista.size(), scroll + filasVisibles); i++) {
            VigilanteLog.Evento e = lista.get(i);
            int color = colorDeNivel(e.nivel());

            g.drawString(this.font, e.hora(), areaX + 4, y, Paleta.TEXTO_APAGADO, false);
            int x = areaX + 4 + this.font.width("00:00:00") + 5;

            String etiqueta = "[" + e.nivel() + "]";
            g.drawString(this.font, etiqueta, x, y, color, false);
            x += this.font.width("[WARN] ") + 2;

            String cuerpo = e.origen() + ": " + e.mensaje();
            Widgets.lineaRecortada(g, this.font, cuerpo, x, y, areaX + areaAncho - x - 6,
                    esProblema(e.nivel()) ? color : Paleta.TEXTO);
            y += ALTO_LINEA;
        }
        g.disableScissor();

        // Estado abajo: cuantas lineas hay y si sigue pegada al final.
        String estado = lista.size() + " lineas"
                + (autoScroll ? "  ·  siguiendo el final" : "  ·  scroll manual")
                + (soloProblemas ? "  ·  filtrado" : "");
        g.drawString(this.font, estado, 8, this.height - 20, Paleta.TEXTO_APAGADO, false);

        if (lista.isEmpty()) {
            g.drawCenteredString(this.font,
                    "Todavia no entro ninguna linea que coincida.",
                    this.width / 2, areaY + areaAlto / 2, Paleta.TEXTO_APAGADO);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int paso = (int) (delta * 3);
        scroll = Math.max(0, scroll - paso);
        // Desplazarse hacia arriba desengancha el seguimiento; volver al fondo lo reengancha.
        List<VigilanteLog.Evento> lista = visibles();
        int filasVisibles = Math.max(1, areaAlto / ALTO_LINEA);
        autoScroll = scroll >= Math.max(0, lista.size() - filasVisibles);
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(anterior);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
