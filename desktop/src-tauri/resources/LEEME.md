# Recursos que viajan con la app de PC

La CI copia acá la app del celular antes de armar el instalador:

- `cyberia.apk`: el APK firmado de la misma versión.
- `cyberia-apk.json`: el `output-metadata.json` que genera Gradle junto al APK (trae la versión).

Con eso, la PC le ofrece la actualización al celular por el Wi-Fi de casa. En desarrollo esta
carpeta puede quedar sin APK: la PC simplemente no ofrece actualizaciones.
