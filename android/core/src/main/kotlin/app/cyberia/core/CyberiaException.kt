package app.cyberia.core

import java.io.IOException

/** Errores al hablar con la PC, con un mensaje listo para mostrar. */
sealed class CyberiaException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    /** La PC ya no reconoce a este celular (401). Hay que volver a vincular. */
    class Unpaired : CyberiaException("La PC desvinculó este celular. Volvé a escanear el QR.")

    /** El código del QR venció o ya se usó. */
    class PairingExpired(message: String) : CyberiaException(message)

    class PairingRejected(message: String) : CyberiaException(message)

    /** El certificado no coincide con el que se fijó al escanear el QR (RNF-06). */
    class CertificateMismatch(cause: Throwable?) :
        CyberiaException("La PC presentó otro certificado. Por seguridad no me conecto; volvé a vincular.", cause)

    /** El audio cambió en la PC después de publicarse (409). */
    class FileChanged : CyberiaException("Cambió en la PC después de publicarse")

    /** El audio ya no está en la carpeta de la PC. */
    class FileMissing : CyberiaException("Ya no está en la carpeta de la PC")

    class Network(cause: Throwable) : CyberiaException("No se pudo conectar con la PC", cause)

    class Server(val status: Int, message: String) : CyberiaException(message)
}
