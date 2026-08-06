package com.coco.faro.client;

import com.coco.faro.config.ConfigFaro;
import com.coco.faro.diag.MotorDiagnostico;
import com.coco.faro.diag.VigilanteOpenGL;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Interceptador de errores de OpenGL, y lo que se puede decir de las texturas rotas.
 *
 * Las dos cosas van juntas porque el usuario llega aca con el mismo sintoma: "se
 * ve mal". Esta pantalla separa las dos causas posibles y dice con precision que
 * puede y que no puede detectar cada camino.
 */
public class PantallaOpenGL extends PantallaHerramienta {

    public PantallaOpenGL(Screen anterior) {
        super(anterior, "Errores graficos", "OpenGL y recursos que no cargaron");
    }

    @Override
    protected void botones(int x, int y, int ancho) {
        int mitad = (ancho - 6) / 2;
        VigilanteOpenGL v = VigilanteOpenGL.get();

        addRenderableWidget(Button.builder(
                        Component.literal(v.activo() ? "Dejar de vigilar OpenGL"
                                : "Vigilar OpenGL (cuesta FPS)"),
                        b -> {
                            boolean nuevo = !v.activo();
                            v.activar(nuevo);
                            ConfigFaro.INSTANCIA.vigilarOpenGL.set(nuevo);
                            ConfigFaro.INSTANCIA.vigilarOpenGL.save();
                            refrescar();
                        })
                .bounds(x, y, mitad, 20).build());

        addRenderableWidget(Button.builder(
                        Component.literal("Recargar texturas y modelos"),
                        b -> {
                            Minecraft.getInstance().reloadResourcePacks();
                            AlertasSonoras.listo();
                            onClose();
                        })
                .bounds(x + mitad + 6, y, mitad, 20).build());
    }

    @Override
    protected int contenido(GuiGraphics g, int x, int y, int ancho) {
        VigilanteOpenGL v = VigilanteOpenGL.get();

        y = veredicto(g, x, y, ancho, v.veredicto(),
                v.totalDetectados() > 0 ? Paleta.ERROR : (v.activo() ? Paleta.OK : Paleta.TEXTO_APAGADO));

        // --- Errores de OpenGL
        y = seccion(g, x, y, ancho, "Errores de OpenGL");
        y = Widgets.parrafo(g, this.font,
                "OpenGL no lanza excepciones: cuando algo sale mal deja un codigo de error y "
                        + "sigue. Si nadie lo consulta, lo unico que ves es un bloque negro y "
                        + "violeta o una entidad invisible, y en el log no hay una sola linea.\n\n"
                        + "Esto pregunta por ese codigo una vez por cuadro. Lo que se detecta es "
                        + "el error grafico de fondo — la causa — no el sintoma visible.",
                x, y, ancho, Paleta.TEXTO_TENUE, 10) + 6;

        if (v.activo()) {
            y = fila(g, x, y, ancho, "Cuadros revisados", String.valueOf(v.cuadrosRevisados()),
                    Paleta.TEXTO_TENUE);
            y = fila(g, x, y, ancho, "Errores detectados", String.valueOf(v.totalDetectados()),
                    v.totalDetectados() > 0 ? Paleta.ERROR : Paleta.OK);
            y += 4;
        }

        List<VigilanteOpenGL.ErrorGl> errores = v.errores();
        if (errores.isEmpty()) {
            y = vacio(g, x, y, ancho, v.activo()
                    ? "Ningun error hasta ahora. Si algo se ve mal, no es un problema de OpenGL: "
                      + "mirá la seccion de texturas mas abajo."
                    : "La vigilancia esta apagada. Prendela con el boton de abajo, reproducí el "
                      + "problema visual, y volvé.");
        } else {
            for (VigilanteOpenGL.ErrorGl e : errores) {
                int color = e.codigo() == org.lwjgl.opengl.GL11.GL_OUT_OF_MEMORY
                        ? Paleta.ERROR : Paleta.ADVERTENCIA;

                Widgets.tarjeta(g, x, y, ancho, 24, color);
                g.drawString(this.font, e.nombre(), x + 7, y + 5, color, false);
                String veces = e.repeticiones() + "x";
                g.drawString(this.font, veces, x + ancho - this.font.width(veces) - 6, y + 5,
                        Paleta.TEXTO_TENUE, false);
                Widgets.lineaRecortada(g, this.font, "en la fase: " + e.fase(),
                        x + 7, y + 15, ancho - 14, Paleta.TEXTO_APAGADO);
                y += 28;

                y = Widgets.parrafo(g, this.font, VigilanteOpenGL.explicar(e.codigo()),
                        x + 6, y, ancho - 12, Paleta.TEXTO_TENUE, 6) + 8;
            }

            y = Widgets.parrafo(g, this.font,
                    "OJO con la atribucion: el error queda pendiente hasta que alguien lo "
                            + "consulta, asi que la fase dice DONDE se detecto, no que mod lo "
                            + "produjo. Para acotarlo, sacá los mods de render de a uno — o usá "
                            + "la biseccion automatica de Faro, que hace exactamente eso.",
                    x, y, ancho, Paleta.ADVERTENCIA, 8) + 8;
        }

        // --- Texturas y modelos faltantes
        y = seccion(g, x, y, ancho, "Texturas y modelos que no cargaron");
        MotorDiagnostico motor = MotorDiagnostico.get();
        int faltantes = motor == null ? 0 : motor.vigilante().texturasFaltantes();

        y = fila(g, x, y, ancho, "Detectados en el log de esta sesion",
                String.valueOf(faltantes), faltantes > 0 ? Paleta.ADVERTENCIA : Paleta.OK);
        y += 4;

        y = Widgets.parrafo(g, this.font,
                "Esto se detecta leyendo el log: cuando un recurso no carga, el juego escribe "
                        + "'Missing texture' o 'Unable to load model' con el nombre exacto. Eso "
                        + "es dato duro y dice de que mod es.\n\n"
                        + "Lo que Faro NO hace, y conviene que lo sepas: no mira el render real. "
                        + "Ver que un bloque salio negro-y-violeta en pantalla exigiria "
                        + "engancharse al pipeline grafico y comparar lo dibujado contra lo "
                        + "esperado, cuadro a cuadro. Eso costaria mas rendimiento del que "
                        + "cualquier diagnostico justifica.\n\n"
                        + "En la practica no hace falta: todo bloque negro-y-violeta deja su "
                        + "linea en el log. Si ves uno y aca dice cero, el recurso cargo bien y "
                        + "el problema es otro — probablemente un error de OpenGL de los de "
                        + "arriba, o un shader.",
                x, y, ancho, Paleta.TEXTO_TENUE, 20) + 8;

        if (motor != null && faltantes > 0) {
            y = seccion(g, x, y, ancho, "Quien los reporta");
            var ranking = motor.vigilante().rankingOrigenes(8);
            if (!ranking.isEmpty()) {
                int max = ranking.get(0).getValue();
                for (var e : ranking) {
                    y = barraDeRanking(g, x, y, ancho, e.getKey(), String.valueOf(e.getValue()),
                            e.getValue() / (float) max, Paleta.ADVERTENCIA);
                }
            }
            y += 4;
            y = Widgets.parrafo(g, this.font,
                    "Recargar texturas y modelos (el boton de abajo, equivale a F3+T) puede "
                            + "arreglar los que fallaron por un problema temporal. Los que faltan "
                            + "de verdad van a volver a fallar: en ese caso el mod esta incompleto "
                            + "o le falta una dependencia de recursos.",
                    x, y, ancho, Paleta.NEUTRO, 6);
        }
        return y + 8;
    }
}
