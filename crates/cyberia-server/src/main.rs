//! Servidor de Cyberia sin interfaz.
//!
//! Sirve para la fase 0 (prueba de concepto), para depurar la app Android sin la app de bandeja y
//! para las pruebas de punta a punta del cliente Kotlin.
//!
//! ```text
//! cyberia-server --folder ~/Música/Cyberia [--data-dir ./cyberia-data] [--port 47474]
//!                [--name "Mi PC"] [--bind 0.0.0.0] [--no-mdns] [--pair] [--sync]
//! ```
//!
//! Comandos por la entrada estándar: `sync`, `pair`, `unpair`, `status`, `quit`.
//! Las líneas que empiezan con una palabra en mayúsculas (`LISTENING`, `PAIR_URI`, `SYNCED`,
//! `EVENT`, `ERROR`) son para que las lean otros programas.

use anyhow::{bail, Context};
use cyberia_core::{Core, CoreOptions};
use std::io::Write;
use std::net::IpAddr;
use std::path::PathBuf;
use std::sync::Arc;
use tokio::io::{AsyncBufReadExt, BufReader};

struct Args {
    data_dir: PathBuf,
    folder: Option<PathBuf>,
    port: Option<u16>,
    name: Option<String>,
    bind: Option<IpAddr>,
    mdns: bool,
    pair: bool,
    sync: bool,
    qr: bool,
}

fn parse_args() -> anyhow::Result<Args> {
    let mut args = Args {
        data_dir: PathBuf::from("cyberia-data"),
        folder: None,
        port: None,
        name: None,
        bind: None,
        mdns: true,
        pair: false,
        sync: false,
        qr: true,
    };
    let mut it = std::env::args().skip(1);
    while let Some(arg) = it.next() {
        let mut value = |name: &str| it.next().with_context(|| format!("falta el valor de {name}"));
        match arg.as_str() {
            "--data-dir" => args.data_dir = value("--data-dir")?.into(),
            "--folder" => args.folder = Some(value("--folder")?.into()),
            "--port" => args.port = Some(value("--port")?.parse().context("puerto inválido")?),
            "--name" => args.name = Some(value("--name")?),
            "--bind" => args.bind = Some(value("--bind")?.parse().context("IP inválida")?),
            "--no-mdns" => args.mdns = false,
            "--no-qr" => args.qr = false,
            "--pair" => args.pair = true,
            "--sync" => args.sync = true,
            "-h" | "--help" => {
                println!(
                    "uso: cyberia-server --folder <carpeta> [--data-dir <dir>] [--port <n>] [--name <nombre>]\n\
                     \x20                     [--bind <ip>] [--no-mdns] [--no-qr] [--pair] [--sync]\n\
                     comandos: sync, pair, unpair, status, quit"
                );
                std::process::exit(0);
            }
            other => bail!("argumento desconocido: {other}"),
        }
    }
    Ok(args)
}

fn out(line: impl AsRef<str>) {
    let mut stdout = std::io::stdout().lock();
    let _ = writeln!(stdout, "{}", line.as_ref());
    let _ = stdout.flush();
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info,mdns_sd=warn".into()),
        )
        .with_writer(std::io::stderr)
        .init();

    let args = parse_args()?;
    let mut options = CoreOptions::new(&args.data_dir);
    options.port = args.port;
    options.pc_name = args.name.clone();
    options.advertise_mdns = args.mdns;
    if let Some(bind) = args.bind {
        options.bind_ip = bind;
    }
    let core = Core::open(options)?;
    if let Some(folder) = &args.folder {
        core.set_folder(folder)?;
    }
    let addr = core.start().await?;
    out(format!("LISTENING {addr}"));
    out(format!("FINGERPRINT {}", core.identity().fingerprint));

    let mut events = core.subscribe();
    tokio::spawn(async move {
        while let Ok(event) = events.recv().await {
            if matches!(event, cyberia_core::CoreEvent::StateChanged) {
                continue;
            }
            if let Ok(json) = serde_json::to_string(&event) {
                out(format!("EVENT {json}"));
            }
        }
    });

    if args.sync {
        run(&core, "sync", args.qr).await;
    }
    if args.pair {
        run(&core, "pair", args.qr).await;
    }

    let mut lines = BufReader::new(tokio::io::stdin()).lines();
    loop {
        tokio::select! {
            line = lines.next_line() => match line? {
                Some(line) => {
                    let cmd = line.trim();
                    if cmd == "quit" || cmd == "exit" {
                        break;
                    }
                    run(&core, cmd, args.qr).await;
                }
                // Sin entrada estándar (por ejemplo, como servicio) se queda corriendo.
                None => {
                    tokio::signal::ctrl_c().await?;
                    break;
                }
            },
            _ = tokio::signal::ctrl_c() => break,
        }
    }
    core.shutdown();
    Ok(())
}

async fn run(core: &Arc<Core>, cmd: &str, show_qr: bool) {
    match cmd {
        "" => {}
        "sync" => match core.sync_now().await {
            Ok(o) => out(format!(
                "SYNCED {} {} files={} bytes={} phone_connected={}",
                o.version, o.changed, o.files, o.bytes, o.phone_connected
            )),
            Err(err) => out(format!("ERROR {err}")),
        },
        "pair" => match core.start_pairing() {
            Ok(offer) => {
                if show_qr {
                    if let Ok(qr) = cyberia_core::pairing::qr_terminal(&offer.uri) {
                        eprintln!("{qr}");
                    }
                    eprintln!("Escaneá el QR con la app Cyberia. Vence en 5 minutos.");
                }
                out(format!("PAIR_URI {}", offer.uri));
            }
            Err(err) => out(format!("ERROR {err:#}")),
        },
        "unpair" => match core.unpair() {
            Ok(()) => out("UNPAIRED"),
            Err(err) => out(format!("ERROR {err:#}")),
        },
        "status" => match serde_json::to_string(&core.snapshot()) {
            Ok(json) => out(format!("STATUS {json}")),
            Err(err) => out(format!("ERROR {err}")),
        },
        other => out(format!("ERROR comando desconocido: {other}")),
    }
}
