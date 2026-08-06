package com.coco.faro.client;

import com.coco.faro.diag.Diagnostico;
import com.coco.faro.diag.MotorDiagnostico;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Detalle tecnico crudo: evidencia y stacktrace, tal cual salieron del reporte.
 *
 * Existe para que el usuario pueda verificar por su cuenta la conclusion de Faro
 * (o mandarsela a alguien que sepa) en vez de tener que confiar a ciegas.
 */
public class PantallaDetalle extends Screen {

    private final Screen anterior;
    private final Diagnostico diag;
    private final List<String> lineas = new ArrayList<>();

    private int desplazamiento = 0;
    private int lineasVisibles = 10;

    public PantallaDetalle(Screen anterior, Diagnostico diag) {
        super(Component.literal("Faro — detalle"));
        this.anterior = anterior;
        this.diag = diag;
    }

    @Override
    protected void init() {
        lineas.clear();

        diag.archivoAnalizado().ifPresent(p -> lineas.add("§7Archivo: §f" + p.getFileName()));
        if (!diag.descripcion().isEmpty()) {
            lineas.add("§7Description: §f" + diag.descripcion());
        }
        if (!diag.excepcionPrincipal().isEmpty()) {
            lineas.add("§7Excepcion: §c" + diag.excepcionPrincipal());
        }

        if (!diag.evidencia().isEmpty()) {
            lineas.add("");
            lineas.add("§eEvidencia usada para el diagnostico:");
            for (String e : diag.evidencia()) {
                lineas.add("§7 • §f" + e);
            }
        }

        if (!diag.lineasStack().isEmpty()) {
            lineas.add("");
            lineas.add("§eStacktrace (primeras " + diag.lineasStack().size() + " lineas):");
            for (String s : diag.lineasStack()) {
                lineas.add("§8 " + s);
            }
        }

        if (lineas.isEmpty()) {
            lineas.add("§7Sin detalle disponible.");
        }

        int alturaLista = this.height - 100;
        lineasVisibles = Math.max(4, alturaLista / 10);

        int anchoBoton = 130;
        addRenderableWidget(Button.builder(Component.literal("Abrir crash report"), b -> abrir())
                .bounds(this.width / 2 - anchoBoton - 4, this.height - 30, anchoBoton, 20)
                .build());

        addRenderableWidget(Button.builder(Component.literal("Volver"), b -> onClose())
                .bounds(this.width / 2 + 4, this.height - 30, anchoBoton, 20)
                .build());
    }

    private void abrir() {
        diag.archivoAnalizado().ifPresentOrElse(
                p -> Util.getPlatform().openFile(p.toFile()),
                () -> {
                    MotorDiagnostico motor = MotorDiagnostico.get();
                    if (motor != null) {
                        Util.getPlatform().openFile(motor.carpetaCrashReports().toFile());
                    }
                });
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);

        g.drawCenteredString(this.font, "Detalle tecnico", this.width / 2, 14, Paleta.TEXTO_TITULO);

        int x = 20;
        int y = 34;
        int fin = Math.min(lineas.size(), desplazamiento + lineasVisibles);
        for (int i = desplazamiento; i < fin; i++) {
            String texto = lineas.get(i);
            // Recorte duro para que nunca se desborde a lo ancho.
            String recortado = this.font.plainSubstrByWidth(texto, this.width - 40);
            g.drawString(this.font, recortado, x, y, Paleta.TEXTO, false);
            y += 10;
        }

        if (lineas.size() > lineasVisibles) {
            String pos = (desplazamiento + 1) + "-" + fin + " de " + lineas.size()
                    + "   (rueda del mouse para desplazar)";
            g.drawCenteredString(this.font, pos, this.width / 2, this.height - 46, Paleta.TEXTO_TENUE);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int max = Math.max(0, lineas.size() - lineasVisibles);
        desplazamiento = Math.max(0, Math.min(max, desplazamiento - (int) Math.signum(delta) * 3));
        return true;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(anterior);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
