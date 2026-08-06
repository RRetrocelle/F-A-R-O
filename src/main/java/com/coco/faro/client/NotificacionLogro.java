package com.coco.faro.client;

import com.coco.faro.diag.Severidad;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Notificacion con el formato de los logros de Minecraft, con un faro en pixel
 * art cuya luz cambia de color segun la gravedad.
 *
 * Reemplaza el texto suelto en la esquina, que era feo y se leia como un error
 * del juego mas que como un aviso de Faro.
 *
 * Diferencias a proposito con un logro de vanilla:
 *   - dura 20 segundos en vez de ~5, porque hay que poder leerlo y clickearlo;
 *   - tiene un "Leer mas" clickeable que abre Faro en la pestana correcta;
 *   - se acumulan en cola: si entran tres avisos juntos, se muestran de a uno.
 *
 * Todo el estado vive aca y se actualiza en el render del HUD, sin hilos ni
 * asignaciones por frame: la cola solo se toca cuando entra o sale un aviso.
 */
public final class NotificacionLogro {

    /** A que pestana lleva el "Leer mas". */
    public enum Destino { RESUMEN, PROBLEMAS, CRASH, RENDIMIENTO }

    public record Aviso(String titulo, String resumen, Severidad gravedad, Destino destino) {
    }

    /** Configurable desde los ajustes: se lee cada vez, no se cachea. */
    private static long duracionMs() {
        try {
            return com.coco.faro.config.ConfigFaro.INSTANCIA.segundosNotificacion.get() * 1000L;
        } catch (Throwable t) {
            return 20_000L;
        }
    }

    private static final long DURACION_MS = 20_000L;
    private static final int ANCHO = 200;
    private static final int ALTO = 40;
    private static final int MAX_COLA = 5;

    private static final Deque<Aviso> cola = new ArrayDeque<>();
    private static Aviso actual;
    private static long mostradoDesde = 0L;

    /** Rectangulo del "Leer mas" del frame actual, para el click. */
    private static int leerMasX, leerMasY, leerMasAncho, leerMasAlto;

    private NotificacionLogro() {
    }

    /** Encola un aviso. Si ya hay uno igual esperando, no se duplica. */
    public static void mostrar(Aviso aviso) {
        if (aviso == null) {
            return;
        }
        synchronized (cola) {
            if (actual != null && actual.titulo().equals(aviso.titulo())) {
                return;
            }
            for (Aviso a : cola) {
                if (a.titulo().equals(aviso.titulo())) {
                    return;
                }
            }
            if (cola.size() >= MAX_COLA) {
                cola.removeFirst();
            }
            cola.addLast(aviso);
        }
    }

    /** Dibuja el aviso activo. Se llama desde el overlay del HUD. */
    public static void render(GuiGraphics g, int anchoPantalla) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options.hideGui) {
            return;
        }
        if (!com.coco.faro.config.ConfigFaro.INSTANCIA.notificacionesActivas.get()) {
            return;
        }

        long ahora = System.currentTimeMillis();
        synchronized (cola) {
            if (actual == null || ahora - mostradoDesde > DURACION_MS) {
                actual = cola.pollFirst();
                mostradoDesde = ahora;
            }
        }
        if (actual == null) {
            leerMasAncho = 0;
            return;
        }

        // Entrada y salida deslizante, como el logro de vanilla.
        long transcurrido = ahora - mostradoDesde;
        float desliz = 1f;
        if (transcurrido < 400) {
            desliz = transcurrido / 400f;
        } else if (transcurrido > DURACION_MS - 400) {
            desliz = (DURACION_MS - transcurrido) / 400f;
        }
        desliz = Math.max(0f, Math.min(1f, desliz));

        int x = anchoPantalla - (int) (ANCHO * desliz) - 6;
        int y = 6;

        int acento = switch (actual.gravedad()) {
            case CRITICA -> Paleta.ERROR;
            case ALTA -> Paleta.ADVERTENCIA;
            case MEDIA -> Paleta.NEUTRO;
            case INFO -> Paleta.TEXTO_TENUE;
        };

        g.fill(x, y, x + ANCHO, y + ALTO, 0xE0100D1A);
        Widgets.borde(g, x, y, ANCHO, ALTO, acento);

        dibujarFaro(g, x + 5, y + 6, acento);

        int tx = x + 32;
        Widgets.lineaRecortada(g, mc.font, actual.titulo(), tx, y + 6, ANCHO - 38, acento);
        Widgets.lineaRecortada(g, mc.font, actual.resumen(), tx, y + 17, ANCHO - 38, Paleta.TEXTO);

        String leerMas = "Leer mas";
        leerMasX = tx;
        leerMasY = y + 28;
        leerMasAncho = mc.font.width(leerMas);
        leerMasAlto = 9;
        g.drawString(mc.font, leerMas, leerMasX, leerMasY, Paleta.OK, false);
        g.fill(leerMasX, leerMasY + 9, leerMasX + leerMasAncho, leerMasY + 10,
                Paleta.conAlfa(Paleta.OK, 0.6f));

        // Barra de tiempo restante, para que se entienda que se va a ir solo.
        int restante = (int) (ANCHO * (1.0 - transcurrido / (double) DURACION_MS));
        g.fill(x, y + ALTO - 1, x + Math.max(0, restante), y + ALTO,
                Paleta.conAlfa(acento, 0.7f));
    }

    /** Faro chico de 10x14, con la luz del color de la gravedad. */
    private static void dibujarFaro(GuiGraphics g, int x, int y, int colorLuz) {
        String[] icono = {
                "...kkkk...", "..kwwwwk..", ".kkkkkkkk.", ".kyyyyyyk.",
                ".kyyyyyyk.", ".kkkkkkkk.", ".kwwwwwwk.", ".krrrrrrk.",
                "kwwwwwwwwk", "krrrrrrrrk", "kwwwwwwwwk", "kkkkkkkkkk",
                "gggggggggg", "gggggggggg",
        };
        for (int fy = 0; fy < icono.length; fy++) {
            for (int fx = 0; fx < icono[fy].length(); fx++) {
                int c = switch (icono[fy].charAt(fx)) {
                    case 'k' -> 0xFF12161C;
                    case 'w' -> 0xFFE6EDF3;
                    case 'r' -> 0xFFF85149;
                    case 'y' -> colorLuz;
                    case 'g' -> 0xFF46505C;
                    default -> 0;
                };
                if (c != 0) {
                    g.fill(x + fx, y + fy, x + fx + 1, y + fy + 1, c);
                }
            }
        }
    }

    /**
     * Chequea si el click cayo sobre "Leer mas".
     *
     * Como el HUD no recibe clicks, se consulta desde el manejador de teclado y
     * mouse del cliente. Devuelve el destino, o null si no correspondia.
     */
    public static Destino clickEnLeerMas(double mouseX, double mouseY) {
        if (actual == null || leerMasAncho <= 0) {
            return null;
        }
        boolean dentro = mouseX >= leerMasX && mouseX <= leerMasX + leerMasAncho
                && mouseY >= leerMasY - 2 && mouseY <= leerMasY + leerMasAlto + 2;
        if (!dentro) {
            return null;
        }
        Destino d = actual.destino();
        descartarActual();
        return d;
    }

    public static void descartarActual() {
        synchronized (cola) {
            actual = null;
            mostradoDesde = 0L;
            leerMasAncho = 0;
        }
    }

    public static boolean hayAvisoVisible() {
        return actual != null;
    }
}
