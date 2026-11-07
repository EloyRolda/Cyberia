// Sin consola extra en Windows en las versiones de producción.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    cyberia_desktop_lib::run()
}
