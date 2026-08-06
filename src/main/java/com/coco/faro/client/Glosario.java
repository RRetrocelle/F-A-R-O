package com.coco.faro.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicaciones en castellano simple de los terminos tecnicos que aparecen en la
 * interfaz, para mostrarlas al pasar el mouse.
 *
 * Criterio de redaccion: escritas para alguien que juega Minecraft y no sabe
 * programar. Nada de "hilo del servidor" ni "heap"; se explica que significa en
 * la practica y que deberia ver el usuario si el numero esta mal.
 */
public final class Glosario {

    public record Termino(String titulo, String explicacion) {
    }

    private static final Map<String, Termino> TERMINOS = new LinkedHashMap<>();

    static {
        poner("tps", "TPS",
                "Cuantas veces por segundo se actualiza el mundo del juego. Lo ideal es 20. "
                        + "Si baja, todo se mueve lento: los mobs, las maquinas, el crecimiento "
                        + "de cultivos. No es lo mismo que los FPS.");

        poner("tick", "Tick",
                "Un 'latido' del juego. El mundo se actualiza 20 veces por segundo, o sea un "
                        + "tick cada 50 milisegundos.");

        poner("tiempo de tick", "Tiempo de tick",
                "Cuanto tarda el juego en procesar cada latido del mundo. Tiene 50 milisegundos "
                        + "para hacerlo. Si tarda mas, el juego se atrasa y sentis tirones. "
                        + "Cuanto mas bajo, mejor.");

        poner("p95", "p95",
                "El 95% de los ticks tardaron menos que este numero. Sirve mas que el promedio "
                        + "porque muestra los momentos malos, que son los que se sienten al jugar. "
                        + "El promedio los esconde.");

        poner("dependencia", "Dependencia",
                "Un mod que otro mod necesita para funcionar. Por ejemplo, muchos mods de "
                        + "criaturas necesitan GeckoLib para animarlas. Si falta, el mod que la "
                        + "necesita no arranca.");

        poner("rango pedido", "Rango de version",
                "Que versiones de la dependencia sirven. '[6.0.7,)' significa 'la 6.0.7 o "
                        + "cualquiera mas nueva'. '[1.0,2.0)' significa 'de la 1.0 hasta la 2.0, "
                        + "sin incluirla'. Si la instalada no entra, el mod no carga.");

        poner("memoria", "Memoria (RAM)",
                "Cuanta memoria le asignaste al juego en el launcher. Con muchos mods hace "
                        + "falta bastante. Si se llena, el juego se cierra solo. Ojo: darle "
                        + "demasiada tampoco ayuda, empeora los tirones.");

        poner("jarinjar", "Mod anidado (JarInJar)",
                "Algunos mods traen sus dependencias empaquetadas adentro de su propio archivo. "
                        + "Aunque no las veas sueltas en la carpeta, estan y funcionan.");

        poner("modid", "modId",
                "El nombre interno de un mod, el que usa el juego. No siempre coincide con el "
                        + "nombre que ves en pantalla ni con el del archivo.");

        poner("mixin", "Mixin",
                "La tecnica que usan los mods para modificar el juego por dentro. Cuando dos "
                        + "mods intentan cambiar la misma parte, chocan y el juego se cierra.");

        poner("stacktrace", "Stacktrace",
                "El rastro que deja un error: la lista de funciones por las que paso el juego "
                        + "justo antes de fallar. Se lee de arriba hacia abajo.");

        poner("crash report", "Crash report",
                "El archivo que Minecraft escribe cuando se cierra por un error. Queda guardado "
                        + "en la carpeta crash-reports.");

        poner("confianza", "Confianza",
                "Que tan seguro esta Faro de haber encontrado al culpable. 'Alta' es que Forge "
                        + "lo nombro directamente. 'Media' es deduccion a partir del error. "
                        + "'Ninguna' significa que no sabe, y en ese caso no ofrece arreglarlo.");

        poner("sha-1", "SHA-1",
                "Una huella digital del archivo. Faro la compara al descargar algo: si no "
                        + "coincide con la oficial, la descarga vino mal o el archivo fue "
                        + "cambiado, y no lo instala.");

        poner("loader", "Modloader",
                "El programa que carga los mods. Este pack usa Forge. Un mod hecho para Fabric "
                        + "o NeoForge no funciona en Forge, aunque este en la carpeta.");

        poner("cpu", "Uso de CPU",
                "Cuanto trabajo le esta dando el juego a tu procesador. 'Minecraft' es solo el "
                        + "juego; 'Sistema' es todo lo que corre en la PC. Si Minecraft esta "
                        + "alto y el tick tambien, el cuello de botella es el procesador.");

        poner("gpu", "Uso de GPU",
                "Cuanto trabajo tiene tu placa de video. Java no puede leerlo por su cuenta, "
                        + "asi que Faro lo pide al driver. Solo funciona con placas NVIDIA.");

        poner("vram", "VRAM",
                "La memoria propia de la placa de video. Si se llena, bajan los FPS aunque "
                        + "te sobre RAM normal. Suele pasar con packs de texturas grandes.");

        poner("autoconsumo", "Costo de Faro",
                "Cuanto procesador consumio Faro desde que abriste el juego. Esta a la vista "
                        + "a proposito: una herramienta que mide rendimiento tiene que poder "
                        + "demostrar que no es parte del problema.");

        poner("solapamiento", "Posible solapamiento",
                "Dos mods que probablemente hacen lo mismo. Esto NO se puede saber con "
                        + "certeza mirando los archivos: sale de una lista armada a mano, "
                        + "asi que conviene que lo revises vos antes de sacar nada.");

        poner("conflicto declarado", "Conflicto declarado",
                "Un mod avisa en su propia configuracion que no puede convivir con otro. "
                        + "Cuando pasa esto no hay duda: hay que sacar uno de los dos.");

        poner("alternativa", "Alternativa sugerida",
                "Un mod que quedo discontinuado o que fue reemplazado por otro mejor. "
                        + "La lista la armamos a mano, asi que puede faltar alguno.");

        poner("pico", "Tiron",
                "Un momento en que el juego tardo mucho mas de lo normal en actualizarse. "
                        + "Es lo que se siente como un frenon de golpe.");

        poner("heuristica", "Heuristica",
                "Reglas escritas a mano a partir de errores conocidos. No es adivinanza ni "
                        + "inteligencia artificial: cada conclusion se puede rastrear hasta la "
                        + "regla que la produjo.");
    }

    private static void poner(String clave, String titulo, String explicacion) {
        TERMINOS.put(clave, new Termino(titulo, explicacion));
    }

    private Glosario() {
    }

    public static Termino buscar(String clave) {
        return clave == null ? null : TERMINOS.get(clave.toLowerCase());
    }

    /**
     * Dibuja el globo de ayuda cerca del cursor, acomodandolo para que nunca se
     * salga de la pantalla.
     */
    public static void dibujar(GuiGraphics g, Font font, Termino t,
                               int mouseX, int mouseY, int anchoPantalla, int altoPantalla) {
        if (t == null) {
            return;
        }
        int anchoMax = Math.min(220, anchoPantalla - 20);
        List<FormattedCharSequence> lineas =
                font.split(Component.literal(t.explicacion()), anchoMax - 10);

        int ancho = anchoMax;
        int alto = 16 + lineas.size() * 10;

        int x = mouseX + 10;
        int y = mouseY - alto / 2;
        if (x + ancho > anchoPantalla - 4) {
            x = mouseX - ancho - 10;
        }
        if (x < 4) {
            x = 4;
        }
        if (y < 4) {
            y = 4;
        }
        if (y + alto > altoPantalla - 4) {
            y = altoPantalla - alto - 4;
        }

        g.fill(x, y, x + ancho, y + alto, 0xF0080A0D);
        Widgets.borde(g, x, y, ancho, alto, Paleta.BORDE_ACENTO);

        g.drawString(font, t.titulo(), x + 5, y + 4, Paleta.TEXTO_TITULO, false);
        int yy = y + 15;
        for (FormattedCharSequence l : lineas) {
            g.drawString(font, l, x + 5, yy, Paleta.TEXTO, false);
            yy += 10;
        }
    }
}
