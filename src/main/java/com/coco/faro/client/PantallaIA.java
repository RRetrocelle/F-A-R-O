package com.coco.faro.client;

import com.coco.faro.diag.MotorDiagnostico;
import com.coco.faro.ia.ClienteIA;
import com.coco.faro.ia.ConfigIA;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/**
 * Capa de IA opcional: configuracion, vista previa de lo que se envia, y resultado.
 *
 * Se llega aca solo cuando el diagnostico heuristico devolvio "Sin causa clara".
 * Si la heuristica ya resolvio el caso —como cuando Forge nombra la dependencia
 * faltante— no se consulta nada: seria gastar plata para responder algo que ya
 * sabemos con certeza.
 *
 * La respuesta se muestra siempre marcada como hipotesis y nunca dispara una
 * accion por su cuenta.
 */
public class PantallaIA extends Screen {

    private enum Fase { CONFIG, PREVIA, CONSULTANDO, RESULTADO }

    private final Screen anterior;
    private final String bloqueError;

    private ConfigIA config;
    private Fase fase;
    private EditBox campoKey;
    private volatile ClienteIA.Respuesta respuesta;
    private int scroll = 0;

    private final AnimacionFaro animacion = new AnimacionFaro();

    public PantallaIA(Screen anterior, String bloqueError) {
        super(Component.literal("Faro — consulta a IA"));
        this.anterior = anterior;
        this.bloqueError = bloqueError == null ? "" : bloqueError;
    }

    @Override
    protected void init() {
        MotorDiagnostico motor = MotorDiagnostico.get();
        Path carpetaFaro = motor != null ? motor.carpetaFaro()
                : Minecraft.getInstance().gameDirectory.toPath().resolve("faro");
        config = ConfigIA.get(carpetaFaro);

        if (fase == null) {
            fase = config.habilitada() ? Fase.PREVIA : Fase.CONFIG;
        }

        this.clearWidgets();
        int cx = this.width / 2;
        int ancho = 150;
        int y = this.height - 30;

        switch (fase) {
            case CONFIG -> {
                campoKey = new EditBox(this.font, cx - 150, 96, 300, 20,
                        Component.literal("API key"));
                campoKey.setMaxLength(200);
                campoKey.setValue(config.apiKey());
                addRenderableWidget(campoKey);

                addRenderableWidget(Button.builder(
                                Component.literal("Proveedor: " + config.proveedor().etiqueta),
                                b -> {
                                    ConfigIA.Proveedor[] todos = ConfigIA.Proveedor.values();
                                    int i = (config.proveedor().ordinal() + 1) % todos.length;
                                    config.proveedor(todos[i]);
                                    init();
                                })
                        .bounds(cx - 150, 122, 300, 20).build());

                addRenderableWidget(Button.builder(Component.literal("Guardar"), b -> {
                            config.apiKey(campoKey.getValue());
                            config.guardar();
                            fase = config.habilitada() ? Fase.PREVIA : Fase.CONFIG;
                            init();
                        })
                        .bounds(cx - ancho - 4, y, ancho, 20).build());

                addRenderableWidget(Button.builder(Component.literal("Volver"), b -> onClose())
                        .bounds(cx + 4, y, ancho, 20).build());
            }

            case PREVIA -> {
                addRenderableWidget(Button.builder(Component.literal("Consultar"), b -> consultar())
                        .bounds(cx - ancho - 4, y, ancho, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Cancelar"), b -> onClose())
                        .bounds(cx + 4, y, ancho, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Cambiar key"), b -> {
                            fase = Fase.CONFIG;
                            init();
                        })
                        .bounds(cx - 60, y - 24, 120, 20).build());
            }

            case CONSULTANDO -> {
                // Sin botones mientras hay una consulta en vuelo.
            }

            case RESULTADO -> addRenderableWidget(
                    Button.builder(Component.literal("Volver"), b -> onClose())
                            .bounds(cx - 80, y, 160, 20).build());
        }
    }

    private void consultar() {
        fase = Fase.CONSULTANDO;
        init();
        Thread t = new Thread(() -> {
            respuesta = ClienteIA.consultar(config, bloqueError);
            fase = Fase.RESULTADO;
            Minecraft.getInstance().execute(this::init);
        }, "Faro-ConsultaIA");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        int cx = this.width / 2;
        int ancho = Math.min(this.width - 40, 440);
        int x = cx - ancho / 2;

        g.drawCenteredString(this.font, "Consulta a IA (opcional)", cx, 12, Paleta.VIOLETA);

        switch (fase) {
            case CONFIG -> renderConfig(g, x, ancho, cx);
            case PREVIA -> renderPrevia(g, x, ancho, cx);
            case CONSULTANDO -> {
                animacion.dibujar(g, cx, this.height / 2 - 40, 2);
                g.drawCenteredString(this.font, "Consultando...", cx, this.height / 2 + 20,
                        Paleta.VIOLETA);
            }
            case RESULTADO -> renderResultado(g, x, ancho, cx);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderConfig(GuiGraphics g, int x, int ancho, int cx) {
        int y = 30;
        y = Widgets.parrafo(g, this.font,
                "Faro funciona igual sin esto. La IA es un extra que solo se usa cuando el "
                        + "analisis normal no llego a ninguna conclusion.",
                x, y, ancho, Paleta.TEXTO_TENUE, 3);
        y += 6;
        Widgets.parrafo(g, this.font,
                "Pega tu propia API key. Se guarda en faro/ia-config.json, en tu PC, "
                        + "y no viaja a ningun lado salvo al proveedor que elijas.",
                x, y, ancho, Paleta.NEUTRO, 3);

        g.drawString(this.font, "API key", x, 86, Paleta.TEXTO_APAGADO, false);

        int yy = 148;
        g.drawString(this.font, "Modelo: " + config.modelo(), x, yy, Paleta.TEXTO_TENUE, false);
        yy += 12;
        g.drawString(this.font, "Consultas hechas hasta ahora: " + config.consultasHechas(),
                x, yy, Paleta.TEXTO_APAGADO, false);
        yy += 14;
        Widgets.parrafo(g, this.font,
                "Si tu proveedor cobra por uso, cada consulta tiene costo. El contador de arriba "
                        + "esta para que no te agarre de sorpresa.",
                x, yy, ancho, Paleta.ADVERTENCIA, 3);
    }

    private void renderPrevia(GuiGraphics g, int x, int ancho, int cx) {
        int y = 28;
        g.drawString(this.font, "Esto es TODO lo que se va a enviar", x, y, Paleta.TEXTO_TITULO, false);
        y += 12;
        y = Widgets.parrafo(g, this.font,
                "No se manda la lista de mods, ni rutas de archivos, ni datos de tu PC. "
                        + "Las rutas absolutas se reemplazan por <ruta-local> antes de salir.",
                x, y, ancho, Paleta.TEXTO_TENUE, 3);
        y += 6;

        String previa = ClienteIA.vistaPrevia(bloqueError);
        int altoCaja = this.height - y - 90;
        g.fill(x, y, x + ancho, y + altoCaja, Paleta.FONDO_PANEL);
        Widgets.borde(g, x, y, ancho, altoCaja, Paleta.BORDE_SUAVE);

        g.enableScissor(x, y, x + ancho, y + altoCaja);
        int yy = y + 4 - scroll;
        for (String linea : previa.split("\\R")) {
            if (yy > y - 10 && yy < y + altoCaja) {
                Widgets.lineaRecortada(g, this.font, linea, x + 4, yy, ancho - 8, Paleta.TEXTO_APAGADO);
            }
            yy += 9;
        }
        g.disableScissor();

        int yInfo = y + altoCaja + 4;
        g.drawString(this.font, config.proveedor().etiqueta + "  ·  " + config.modelo()
                        + "  ·  key " + config.keyEnmascarada(),
                x, yInfo, Paleta.TEXTO_APAGADO, false);
        g.drawString(this.font, "consultas hechas: " + config.consultasHechas(),
                x, yInfo + 10, Paleta.TEXTO_APAGADO, false);
    }

    private void renderResultado(GuiGraphics g, int x, int ancho, int cx) {
        ClienteIA.Respuesta r = respuesta;
        if (r == null) {
            return;
        }
        int y = 28;

        if (!r.exito()) {
            g.drawString(this.font, "No se pudo consultar", x, y, Paleta.ERROR, false);
            y += 14;
            Widgets.parrafo(g, this.font, r.error(), x, y, ancho, Paleta.TEXTO_TENUE, 6);
            return;
        }

        // Etiqueta bien visible: esto NO es una deteccion confirmada.
        int anchoBadge = Widgets.badge(g, this.font, "IA: hipotesis, no confirmado",
                x, y, Paleta.VIOLETA);
        y += 18;
        y = Widgets.parrafo(g, this.font,
                "Esto lo escribio un modelo de lenguaje a partir del texto del error. "
                        + "Se puede equivocar. No dispara ninguna accion por su cuenta.",
                x, y, ancho, Paleta.TEXTO_APAGADO, 3);
        y += 6;

        g.fill(x, y, x + ancho, y + 2, Paleta.conAlfa(Paleta.VIOLETA, 0.5f));
        y += 8;

        int altoCaja = this.height - y - 46;
        g.enableScissor(x, y, x + ancho, y + altoCaja);
        int yy = y - scroll;
        for (String linea : r.texto().split("\\R")) {
            yy = Widgets.parrafo(g, this.font, linea, x, yy, ancho, Paleta.TEXTO, 20);
            yy += 2;
        }
        g.disableScissor();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scroll = Math.max(0, scroll - (int) (delta * 16));
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
