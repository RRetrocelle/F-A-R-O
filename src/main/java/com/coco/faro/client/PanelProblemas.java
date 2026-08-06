package com.coco.faro.client;

import com.coco.faro.diag.Problema;
import com.coco.faro.diag.RangoVersion;
import com.coco.faro.diag.Severidad;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * El panel de problemas, en un solo lugar.
 *
 * Lo usan tanto la pestana "Problemas" de {@link PantallaFaro} como la pantalla
 * de rescate que se abre desde el error de arranque de Forge. Es el mismo
 * dibujo, los mismos botones y las mismas reglas de confirmacion en ambos lados:
 * si se cambia el criterio de que es accionable, cambia en los dos a la vez.
 */
public final class PanelProblemas {

    /**
     * El modId aparece siempre entre comillas simples en el titulo, tanto en
     * "X necesita 'y' y no esta" como en "X pide otra version de 'y'". Capturar
     * la primera cita cubre ambos formatos sin encadenar patrones.
     */
    private static final Pattern MODID_EN_TITULO = Pattern.compile("'([A-Za-z0-9_\\-]+)'");

    /** El rango viene como "Rango pedido: [1,2)" o como "pedida: [1,2)". */
    private static final Pattern RANGO_PEDIDO =
            Pattern.compile("(?:Rango pedido|pedida):\\s*(\\S+)");

    private PanelProblemas() {
    }

    /**
     * Dibuja la lista y registra las zonas interactivas.
     *
     * @param zonasOut se le agregan los botones y las ayudas creadas
     * @param yMin     borde superior visible, para no activar zonas recortadas
     * @param yMax     borde inferior visible
     * @return la Y siguiente al ultimo elemento dibujado
     */
    public static int render(GuiGraphics g, Font font, Screen padre, List<Problema> lista,
                             int x, int y, int ancho, int mouseX, int mouseY,
                             int yMin, int yMax, List<Zona> zonasOut) {

        if (lista.isEmpty()) {
            g.drawString(font, "No detecte ningun problema en la instalacion.",
                    x, y + 6, Paleta.OK, false);
            return y + 24;
        }

        for (Problema p : lista) {
            boolean accionable = esAccionable(p);

            int alto = 30;
            alto += font.split(Component.literal(p.detalle()), ancho - 16).size() * 10;
            alto += font.split(Component.literal(p.sugerencia()), ancho - 16).size() * 10;
            if (accionable) {
                alto += 18;
            }

            Widgets.tarjeta(g, x, y, ancho, alto, Paleta.porSeveridad(p.severidad()));

            int bx = x + 8;
            int anchoBadge = Widgets.badge(g, font, p.severidad().etiqueta(), bx, y + 5,
                    Paleta.porSeveridad(p.severidad()));
            bx += anchoBadge + 4;

            // Regla transversal: nada se muestra sin su nivel de certeza al lado.
            com.coco.faro.diag.Certeza certeza = com.coco.faro.diag.Certeza.de(p);
            int anchoCerteza = Widgets.badge(g, font, certeza.etiqueta(), bx, y + 5,
                    colorCerteza(certeza));
            zonasOut.add(Zona.ayuda(bx, y + 5, anchoCerteza, 11, "certeza"));
            bx += anchoCerteza + 5;

            Widgets.lineaRecortada(g, font, p.categoria().etiqueta(),
                    bx, y + 7, x + ancho - bx - 8, Paleta.TEXTO_APAGADO);

            String clave = claveGlosarioDe(p);
            if (clave != null) {
                zonasOut.add(Zona.ayuda(bx, y + 5,
                        font.width(p.categoria().etiqueta()), 11, clave));
            }

            int yy = y + 19;
            Widgets.lineaRecortada(g, font, p.titulo(), x + 8, yy, ancho - 16, Paleta.TEXTO);
            yy += 11;
            yy = Widgets.parrafo(g, font, p.detalle(), x + 8, yy, ancho - 16, Paleta.TEXTO_TENUE, 4);
            yy = Widgets.parrafo(g, font, p.sugerencia(), x + 8, yy, ancho - 16, Paleta.NEUTRO, 4);

            if (accionable) {
                dibujarAccion(g, font, padre, p, x + 8, yy + 2, mouseX, mouseY, yMin, yMax, zonasOut);
            }

            y += alto + 5;
        }
        return y;
    }

    /**
     * Solo llevan boton los problemas con una accion concreta y reversible.
     *
     * Un jar sin metadatos, un rango blando o una version fuera de rango no
     * tienen arreglo automatico honesto: en esos casos el texto explica que
     * hacer y no se ofrece un boton que no arregla nada.
     */
    public static boolean esAccionable(Problema p) {
        return p.categoria() == Problema.Categoria.DEPENDENCIA_AUSENTE
                || p.categoria() == Problema.Categoria.DEPENDENCIA_VERSION
                || p.categoria() == Problema.Categoria.MOD_DUPLICADO
                || p.categoria() == Problema.Categoria.LOADER_INCORRECTO
                || p.categoria() == Problema.Categoria.CONFLICTO_DECLARADO
                || p.categoria() == Problema.Categoria.POSIBLE_SOLAPAMIENTO
                || p.categoria() == Problema.Categoria.ALTERNATIVA_SUGERIDA;
    }

    public static int colorCerteza(com.coco.faro.diag.Certeza c) {
        return switch (c) {
            case ALTA -> Paleta.NEUTRO;
            case MEDIA -> Paleta.VIOLETA;
            case NINGUNA -> Paleta.TEXTO_APAGADO;
        };
    }

    private static String claveGlosarioDe(Problema p) {
        return switch (p.categoria()) {
            case DEPENDENCIA_AUSENTE, DEPENDENCIA_VERSION -> "dependencia";
            case RANGO_BLANDO -> "rango pedido";
            case LOADER_INCORRECTO -> "loader";
            case MOD_DUPLICADO -> "modid";
            case CONFLICTO_DECLARADO -> "conflicto declarado";
            case POSIBLE_SOLAPAMIENTO -> "solapamiento";
            case ALTERNATIVA_SUGERIDA -> "alternativa";
            case SIN_METADATOS -> "jarinjar";
            default -> null;
        };
    }

    private static void dibujarAccion(GuiGraphics g, Font font, Screen padre, Problema p,
                                      int x, int y, int mouseX, int mouseY,
                                      int yMin, int yMax, List<Zona> zonasOut) {
        switch (p.categoria()) {
            case DEPENDENCIA_AUSENTE -> {
                String falta = modIdFaltanteDe(p);
                if (falta == null) {
                    return;
                }
                boton(g, font, x, y, "Instalar " + falta, Paleta.OK, mouseX, mouseY, yMin, yMax,
                        zonasOut,
                        () -> Minecraft.getInstance().setScreen(new PantallaConfirmarInstalacion(
                                padre, falta, rangoPedidoDe(p), p.mod().orElse("un mod"))));
            }
            case DEPENDENCIA_VERSION -> {
                // La dependencia esta, pero en una version que no sirve. La accion
                // util es traer la version correcta, no desactivar nada: sacar el
                // mod que la pide seria resolverlo perdiendo contenido.
                String dep = modIdFaltanteDe(p);
                if (dep == null) {
                    return;
                }
                boton(g, font, x, y, "Instalar version correcta de " + dep, Paleta.ADVERTENCIA,
                        mouseX, mouseY, yMin, yMax, zonasOut,
                        () -> Minecraft.getInstance().setScreen(new PantallaConfirmarInstalacion(
                                padre, dep, rangoPedidoDe(p), p.mod().orElse("un mod"))));
            }
            case ALTERNATIVA_SUGERIDA -> {
                // Dos acciones distintas segun el caso, y ninguna automatica:
                // si el reemplazo ya esta, sobra el viejo; si no, primero se instala.
                com.coco.faro.diag.BaseConflictos.Alternativa alt =
                        com.coco.faro.diag.BaseConflictos.alternativaPara(p.mod().orElse(""));
                if (alt == null) {
                    return;
                }
                if (p.sugerencia().startsWith("Instalá")) {
                    boton(g, font, x, y, "Instalar " + alt.reemplazo(), Paleta.OK,
                            mouseX, mouseY, yMin, yMax, zonasOut,
                            () -> Minecraft.getInstance().setScreen(new PantallaConfirmarInstalacion(
                                    padre, alt.reemplazo(), RangoVersion.de(""), alt.nombreLindo())));
                    return;
                }
                desactivarJarDe(g, font, padre, p, x, y, mouseX, mouseY, yMin, yMax, zonasOut);
            }
            case CONFLICTO_DECLARADO, POSIBLE_SOLAPAMIENTO ->
                    desactivarJarDe(g, font, padre, p, x, y, mouseX, mouseY, yMin, yMax, zonasOut);

            case MOD_DUPLICADO, LOADER_INCORRECTO -> {
                Path jar = p.jar().orElse(null);
                if (jar == null) {
                    return;
                }
                String nombre = jar.getFileName().toString();
                String corto = nombre.length() > 22 ? nombre.substring(0, 20) + "..." : nombre;
                boton(g, font, x, y, "Deshabilitar " + corto, Paleta.ADVERTENCIA,
                        mouseX, mouseY, yMin, yMax, zonasOut,
                        () -> Minecraft.getInstance().setScreen(new PantallaConfirmarDesactivacion(
                                padre, jar, p.mod().orElse(nombre), p.titulo())));
            }
            default -> {
            }
        }
    }

    /** Boton "Deshabilitar" para el jar asociado a un problema. */
    private static void desactivarJarDe(GuiGraphics g, Font font, Screen padre, Problema p,
                                        int x, int y, int mouseX, int mouseY,
                                        int yMin, int yMax, List<Zona> zonasOut) {
        Path jar = p.jar().orElse(null);
        if (jar == null) {
            return;
        }
        String nombre = jar.getFileName().toString();
        String corto = nombre.length() > 22 ? nombre.substring(0, 20) + "..." : nombre;
        boton(g, font, x, y, "Deshabilitar " + corto, Paleta.ADVERTENCIA,
                mouseX, mouseY, yMin, yMax, zonasOut,
                () -> Minecraft.getInstance().setScreen(new PantallaConfirmarDesactivacion(
                        padre, jar, p.mod().orElse(nombre), p.titulo())));
    }

    /** Boton chico de accion, dibujado y registrado como zona. */
    public static void boton(GuiGraphics g, Font font, int x, int y, String etiqueta, int color,
                             int mouseX, int mouseY, int yMin, int yMax,
                             List<Zona> zonasOut, Runnable accion) {
        int ancho = font.width(etiqueta) + 10;
        int alto = 13;
        boolean visible = y >= yMin && y + alto <= yMax;
        boolean hover = visible && mouseX >= x && mouseX < x + ancho
                && mouseY >= y && mouseY < y + alto;

        g.fill(x, y, x + ancho, y + alto, Paleta.conAlfa(color, hover ? 0.40f : 0.20f));
        Widgets.borde(g, x, y, ancho, alto, Paleta.conAlfa(color, hover ? 0.95f : 0.60f));
        g.drawString(font, etiqueta, x + 5, y + 3, hover ? Paleta.TEXTO : color, false);

        zonasOut.add(Zona.boton(x, y, ancho, alto, etiqueta, accion));
    }

    /** Saca el modId de la dependencia problematica del titulo. */
    public static String modIdFaltanteDe(Problema p) {
        Matcher m = MODID_EN_TITULO.matcher(p.titulo());
        return m.find() ? m.group(1) : null;
    }

    /** Saca el rango pedido del detalle del problema. */
    public static RangoVersion rangoPedidoDe(Problema p) {
        Matcher m = RANGO_PEDIDO.matcher(p.detalle());
        return RangoVersion.de(m.find() ? m.group(1) : "");
    }

    /** Cuenta cuantos problemas de cada gravedad hay, para el encabezado. */
    public static String resumen(List<Problema> lista) {
        long criticos = lista.stream().filter(p -> p.severidad() == Severidad.CRITICA).count();
        long altos = lista.stream().filter(p -> p.severidad() == Severidad.ALTA).count();
        if (criticos > 0) {
            return criticos + " criticos, " + altos + " importantes";
        }
        if (altos > 0) {
            return altos + " importantes";
        }
        return lista.size() + " avisos";
    }
}
