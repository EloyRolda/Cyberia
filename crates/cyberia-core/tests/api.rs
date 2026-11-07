//! Prueba de punta a punta de la API HTTPS con un cliente real.

use cyberia_core::{Core, CoreOptions};
use futures_util::StreamExt;
use std::fs;
use std::net::{IpAddr, Ipv4Addr};
use std::time::Duration;

fn client() -> reqwest::Client {
    reqwest::Client::builder()
        // El celular fija la huella del certificado; acá alcanza con aceptarlo.
        .danger_accept_invalid_certs(true)
        .timeout(Duration::from_secs(10))
        .build()
        .unwrap()
}

fn query_param(uri: &str, key: &str) -> String {
    let query = uri.split_once('?').unwrap().1;
    query
        .split('&')
        .find_map(|kv| kv.strip_prefix(&format!("{key}=")))
        .map(|v| urlencoding::decode(v).unwrap().into_owned())
        .unwrap()
}

#[tokio::test]
async fn recorrido_completo() {
    let data = tempfile::tempdir().unwrap();
    let music = tempfile::tempdir().unwrap();
    fs::create_dir_all(music.path().join("Para correr")).unwrap();
    fs::write(music.path().join("Para correr/tema1.mp3"), vec![7u8; 100_000]).unwrap();
    fs::write(music.path().join("intro.ogg"), b"ogg data").unwrap();
    fs::write(music.path().join("leeme.txt"), b"no es audio").unwrap();

    let mut opts = CoreOptions::new(data.path());
    opts.port = Some(0);
    opts.advertise_mdns = false;
    opts.bind_ip = IpAddr::V4(Ipv4Addr::LOCALHOST);
    let core = Core::open(opts).unwrap();
    let addr = core.start().await.unwrap();
    let base = format!("https://{addr}");
    let http = client();

    // Sin carpeta no se publica nada.
    assert!(core.sync_now().await.is_err());
    core.set_folder(music.path()).unwrap();
    let outcome = core.sync_now().await.unwrap();
    assert_eq!(outcome.version, 1);
    assert_eq!(outcome.files, 2);
    let snap = core.snapshot();
    let lib = snap.library.unwrap();
    assert_eq!(lib.unsupported_count, 1);
    assert!(!snap.pending_changes);

    // Sin token: 401.
    let r = http.get(format!("{base}/v1/info")).send().await.unwrap();
    assert_eq!(r.status(), 401);

    // Sin QR activo no se puede vincular.
    let r = http
        .post(format!("{base}/v1/pair"))
        .json(&serde_json::json!({"code": "x", "deviceId": "d1", "deviceName": "Pixel"}))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 410);

    let offer = core.start_pairing().unwrap();
    assert_eq!(query_param(&offer.uri, "fp"), core.identity().fingerprint);
    let code = query_param(&offer.uri, "code");
    let r = http
        .post(format!("{base}/v1/pair"))
        .json(&serde_json::json!({"code": "incorrecto", "deviceId": "d1", "deviceName": "Pixel"}))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 403);
    let r = http
        .post(format!("{base}/v1/pair"))
        .json(&serde_json::json!({"code": code, "deviceId": "d1", "deviceName": "Pixel 8"}))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 200);
    let body: serde_json::Value = r.json().await.unwrap();
    let token = body["token"].as_str().unwrap().to_string();
    assert_eq!(core.snapshot().device.unwrap().name, "Pixel 8");

    // El código sirve una sola vez.
    let r = http
        .post(format!("{base}/v1/pair"))
        .json(&serde_json::json!({"code": code, "deviceId": "d2", "deviceName": "Otro"}))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 410);

    let auth = format!("Bearer {token}");
    let info: serde_json::Value = http
        .get(format!("{base}/v1/info"))
        .header("Authorization", &auth)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(info["manifestVersion"], 1);

    let manifest: serde_json::Value = http
        .get(format!("{base}/v1/manifest"))
        .header("Authorization", &auth)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(manifest["version"], 1);
    let files = manifest["files"].as_array().unwrap();
    assert_eq!(files.len(), 2);
    let tema = files.iter().find(|f| f["path"] == "Para correr/tema1.mp3").unwrap();
    assert_eq!(tema["playlist"], "Para correr");
    assert_eq!(tema["size"], 100_000);
    let id = tema["id"].as_str().unwrap().to_string();
    assert!(id.starts_with("b3:"));

    // Descarga completa y por rangos.
    let r = http
        .get(format!("{base}/v1/files/{id}"))
        .header("Authorization", &auth)
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 200);
    assert_eq!(r.bytes().await.unwrap().len(), 100_000);
    let r = http
        .get(format!("{base}/v1/files/{id}"))
        .header("Authorization", &auth)
        .header("Range", "bytes=60000-")
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 206);
    assert_eq!(r.bytes().await.unwrap().len(), 40_000);

    // Conexión de avisos: primero la versión actual, después la nueva al sincronizar.
    let events = http
        .get(format!("{base}/v1/events"))
        .header("Authorization", &auth)
        .send()
        .await
        .unwrap();
    assert_eq!(events.status(), 200);
    let mut stream = events.bytes_stream();
    let first = String::from_utf8(stream.next().await.unwrap().unwrap().to_vec()).unwrap();
    assert!(first.contains("event: version") && first.contains("\"version\":1"), "{first}");
    assert!(core.snapshot().phone_connected);

    // Un audio cambiado sin publicar no se sirve.
    tokio::time::sleep(Duration::from_millis(20)).await;
    fs::write(music.path().join("Para correr/tema1.mp3"), vec![8u8; 100_001]).unwrap();
    let r = http
        .get(format!("{base}/v1/files/{id}"))
        .header("Authorization", &auth)
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 409);

    core.refresh_library().await.unwrap();
    assert!(core.snapshot().pending_changes);
    let outcome = core.sync_now().await.unwrap();
    assert_eq!(outcome.version, 2);
    assert!(outcome.phone_connected);
    let next = tokio::time::timeout(Duration::from_secs(5), stream.next())
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    let next = String::from_utf8(next.to_vec()).unwrap();
    assert!(next.contains("\"version\":2"), "{next}");

    // Informe del celular.
    let r = http
        .post(format!("{base}/v1/report"))
        .header("Authorization", &auth)
        .json(&serde_json::json!({
            "manifestVersion": 2,
            "downloaded": [{"id": id, "path": "Para correr/tema1.mp3"}],
            "failed": [{"id": "b3:00", "path": "intro.ogg", "reason": "Sin espacio"}],
            "noSpace": true,
            "libraryCount": 1
        }))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 204);
    let report = core.snapshot().last_report.unwrap();
    assert_eq!(report.device_name, "Pixel 8");
    assert_eq!(report.report.downloaded.len(), 1);
    assert!(report.report.no_space);

    // Desvincular desde la PC cierra la conexión de avisos y anula el token.
    core.unpair().unwrap();
    let last = tokio::time::timeout(Duration::from_secs(5), stream.next())
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    assert!(String::from_utf8(last.to_vec()).unwrap().contains("event: unpaired"));
    let r = http
        .get(format!("{base}/v1/manifest"))
        .header("Authorization", &auth)
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 401);
    drop(stream);
    tokio::time::sleep(Duration::from_millis(100)).await;
    assert!(!core.snapshot().phone_connected);

    core.shutdown();
}

#[tokio::test]
async fn el_celular_puede_desvincularse() {
    let data = tempfile::tempdir().unwrap();
    let mut opts = CoreOptions::new(data.path());
    opts.port = Some(0);
    opts.advertise_mdns = false;
    opts.bind_ip = IpAddr::V4(Ipv4Addr::LOCALHOST);
    let core = Core::open(opts).unwrap();
    let addr = core.start().await.unwrap();
    let base = format!("https://{addr}");
    let http = client();

    let offer = core.start_pairing().unwrap();
    let code = query_param(&offer.uri, "code");
    let body: serde_json::Value = http
        .post(format!("{base}/v1/pair"))
        .json(&serde_json::json!({"code": code, "deviceId": "d1", "deviceName": "Moto"}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let auth = format!("Bearer {}", body["token"].as_str().unwrap());
    let r = http
        .delete(format!("{base}/v1/pair"))
        .header("Authorization", &auth)
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 204);
    assert!(core.snapshot().device.is_none());

    // La vinculación se guarda en disco: al reabrir sigue sin celular, y con el mismo certificado.
    let fp = core.identity().fingerprint.clone();
    core.shutdown();
    let reopened = Core::open(CoreOptions::new(data.path())).unwrap();
    assert!(reopened.snapshot().device.is_none());
    assert_eq!(reopened.identity().fingerprint, fp);
}

#[tokio::test]
async fn la_pc_ofrece_la_app_del_celular() {
    let data = tempfile::tempdir().unwrap();
    let res = tempfile::tempdir().unwrap();
    let mut opts = CoreOptions::new(data.path());
    opts.port = Some(0);
    opts.advertise_mdns = false;
    opts.bind_ip = IpAddr::V4(Ipv4Addr::LOCALHOST);
    let core = Core::open(opts).unwrap();
    let addr = core.start().await.unwrap();
    let base = format!("https://{addr}");
    let http = client();

    let offer = core.start_pairing().unwrap();
    let code = query_param(&offer.uri, "code");
    let body: serde_json::Value = http
        .post(format!("{base}/v1/pair"))
        .json(&serde_json::json!({"code": code, "deviceId": "d1", "deviceName": "Moto"}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let auth = format!("Bearer {}", body["token"].as_str().unwrap());

    // Sin APK: la PC no ofrece nada.
    let info: serde_json::Value = http
        .get(format!("{base}/v1/info"))
        .header("Authorization", &auth)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(info["androidApp"].is_null());
    let r = http
        .get(format!("{base}/v1/app/android"))
        .header("Authorization", &auth)
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 404);

    // Con el APK que trae el instalador.
    let apk = vec![42u8; 50_000];
    fs::write(res.path().join(cyberia_core::android_app::APK_FILE), &apk).unwrap();
    fs::write(
        res.path().join(cyberia_core::android_app::METADATA_FILE),
        r#"{"elements":[{"versionCode":7,"versionName":"0.1.7"}]}"#,
    )
    .unwrap();
    core.set_android_app(cyberia_core::android_app::AndroidApp::from_dir(res.path()).unwrap());
    let info: serde_json::Value = http
        .get(format!("{base}/v1/info"))
        .header("Authorization", &auth)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(info["androidApp"]["versionCode"], 7);
    assert_eq!(info["androidApp"]["versionName"], "0.1.7");
    assert_eq!(info["androidApp"]["size"], 50_000);
    assert_eq!(info["androidApp"]["sha256"].as_str().unwrap().len(), 64);
    assert!(info["androidApp"].get("path").is_none());

    // Sin token no se baja.
    let r = http.get(format!("{base}/v1/app/android")).send().await.unwrap();
    assert_eq!(r.status(), 401);
    let r = http
        .get(format!("{base}/v1/app/android"))
        .header("Authorization", &auth)
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 200);
    assert_eq!(
        r.headers()["content-type"],
        "application/vnd.android.package-archive"
    );
    assert_eq!(r.bytes().await.unwrap().to_vec(), apk);

    // El celular informa su versión.
    let r = http
        .post(format!("{base}/v1/report"))
        .header("Authorization", &auth)
        .json(&serde_json::json!({"manifestVersion": 0, "appVersionCode": 5, "appVersionName": "0.1.5"}))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 204);
    let report = core.snapshot().last_report.unwrap();
    assert_eq!(report.report.app_version_code, Some(5));
    core.shutdown();
}
