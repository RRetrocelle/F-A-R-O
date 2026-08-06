package com.coco.faro.client;

import com.coco.faro.diag.Problema;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * Pantalla que se abre desde el boton de Faro en el error de arranque de Forge.
 *
 * Usa {@link PanelProblemas}, el mismo componente que la pestana "Problemas" de
 * la pantalla principal: mismas tarjetas, mismos botones, mismas reglas de
 * confirmacion. No es una interfaz aparte que haya que mantener en paralelo.
 *
 * Todo lo que corre aca es autonomo: cuando la resolucion de dependencias falla,
 * Forge no construye ningun mod, asi que no existe MotorDiagnostico ni ModList.
 */
public class PantallaRescate extends Screen {

    private final Screen anterior;
    private final List<Problema> problemas;
    private final List<Zona> zonas = new ArrayList<>();

    private int scroll = 0;
    private int panelX;
    private int panelAncho;
    private int yContenido;
    private int altoVisible;
    private int altoContenido;

    private Glosario.Termino tooltip;

    public PantallaRescate(Screen anterior, List<Problema> problemas) {
        super(Component.literal("Faro — problemas de arranque"));
        this.anterior = anterior;
        this.problemas = problemas == null ? List.of() : problemas;
    }

    @Override
    protected void init() {
        panelAncho = Math.min(this.width - 24, 460);
        panelX = (this.width - panelAncho) / 2;
        yContenido = 46;
        altoVisible = Math.max(60, this.height - yContenido - 40);

        int ancho = 150;
        int y = this.height - 26;

        addRenderableWidget(Button.builder(Component.literal("Abrir carpeta mods"),
                        b -> Util.getPlatform().openFile(RescateArranque.carpetaMods().toFile()))
                .bounds(this.width / 2 - ancho - 4, y, ancho, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Volver"), b -> onClose())
                .bounds(this.width / 2 + 4, y, ancho, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);

        zonas.clear();
        tooltip = null;

        g.drawCenteredString(this.font, "F A R O", this.width / 2, 10, Paleta.TEXTO_TITULO);

        if (problemas.isEmpty()) {
            g.drawCenteredString(this.font, "No encontre problemas que pueda resolver",
                    this.width / 2, 30, Paleta.ADVERTENCIA);
            int ancho = Math.min(this.width - 40, 420);
            Widgets.parrafo(g, this.font,
                    "El error de arranque no viene de una dependencia faltante, un mod duplicado "
                            + "ni un jar de otro modloader. Desactivar algo a ciegas no lo va a "
                            + "resolver y puede empeorarlo. Mira el detalle en la pantalla anterior "
                            + "o abri logs/latest.log.",
                    this.width / 2 - ancho / 2, 48, ancho, Paleta.TEXTO_TENUE, 6);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }

        g.drawCenteredString(this.font,
                "Forge no pudo arrancar  ·  " + PanelProblemas.resumen(problemas),
                this.width / 2, 24, Paleta.ERROR);
        g.drawCenteredString(this.font,
                "cada accion se confirma por separado antes de tocar un archivo",
                this.width / 2, 35, Paleta.TEXTO_APAGADO);

        g.enableScissor(panelX, yContenido, panelX + panelAncho, yContenido + altoVisible);
        int yInicio = yContenido + 2 - scroll;
        int yFin = PanelProblemas.render(g, this.font, this, problemas,
                panelX + 4, yInicio, panelAncho - 12, mouseX, mouseY,
                yContenido, yContenido + altoVisible, zonas);
        g.disableScissor();

        altoContenido = yFin - yInicio;
        dibujarScroll(g);

        for (Zona z : zonas) {
            if (z.claveGlosario() != null && z.contiene(mouseX, mouseY)
                    && z.visibleEntre(yContenido, yContenido + altoVisible)) {
                tooltip = Glosario.buscar(z.claveGlosario());
            }
        }

        super.render(g, mouseX, mouseY, partialTick);

        if (tooltip != null) {
            Glosario.dibujar(g, this.font, tooltip, mouseX, mouseY, this.width, this.height);
        }
    }

    private void dibujarScroll(GuiGraphics g) {
        if (altoContenido <= altoVisible) {
            return;
        }
        int x = panelX + panelAncho - 3;
        g.fill(x, yContenido, x + 3, yContenido + altoVisible, Paleta.conAlfa(Paleta.BORDE, 0.4f));
        int altoPulgar = Math.max(16, (int) (altoVisible * (altoVisible / (float) altoContenido)));
        int max = altoContenido - altoVisible;
        float frac = max <= 0 ? 0 : scroll / (float) max;
        int y = yContenido + (int) ((altoVisible - altoPulgar) * frac);
        g.fill(x, y, x + 3, y + altoPulgar, Paleta.BORDE_ACENTO);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int boton) {
        for (Zona z : zonas) {
            if (z.accion() != null
                    && z.visibleEntre(yContenido, yContenido + altoVisible)
                    && z.contiene((int) mouseX, (int) mouseY)) {
                z.accion().run();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, boton);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int max = Math.max(0, altoContenido - altoVisible);
        scroll = Math.max(0, Math.min(max, scroll - (int) (delta * 18)));
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
