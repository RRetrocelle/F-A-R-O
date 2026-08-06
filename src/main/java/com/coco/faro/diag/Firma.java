package com.coco.faro.diag;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Una firma de fallo conocida: patron a buscar + que significa + que hacer.
 *
 * Aclaracion honesta sobre el nombre: esto es un sistema experto basado en
 * reglas, no aprendizaje automatico. La "inteligencia" son patrones escritos a
 * mano a partir de fallos reales de Forge. La ventaja es que cada conclusion es
 * auditable — se puede ver exactamente que regla disparo y por que — en vez de
 * salir de una caja negra.
 */
public record Firma(
        String id,
        Pattern patron,
        TipoProblema tipo,
        Severidad severidad,
        String explicacion,
        String sugerencia,
        int peso,
        boolean capturaMod) {

    public static Firma de(String id, String regex, TipoProblema tipo, Severidad sev,
                           String explicacion, String sugerencia, int peso) {
        return new Firma(id, Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
                tipo, sev, explicacion, sugerencia, peso, false);
    }

    /** Variante cuyo grupo 1 del regex captura el modId culpable. */
    public static Firma conMod(String id, String regex, TipoProblema tipo, Severidad sev,
                               String explicacion, String sugerencia, int peso) {
        return new Firma(id, Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
                tipo, sev, explicacion, sugerencia, peso, true);
    }

    /** Resultado de aplicar la firma a un texto. */
    public record Coincidencia(Firma firma, String textoCoincidente, String modCapturado) {
    }

    public Coincidencia aplicar(String texto) {
        Matcher m = patron.matcher(texto);
        if (!m.find()) {
            return null;
        }
        String capturado = null;
        if (capturaMod && m.groupCount() >= 1) {
            capturado = m.group(1);
        }
        String fragmento = m.group();
        if (fragmento.length() > 180) {
            fragmento = fragmento.substring(0, 177) + "...";
        }
        return new Coincidencia(this, fragmento, capturado);
    }
}
