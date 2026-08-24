use std::process::Command;

fn main() {
    println!("cargo:rerun-if-changed=../../../scripts/version.ps1");
    println!("cargo:rerun-if-changed=../../../.git/HEAD");
    println!("cargo:rustc-env=DS_APP_VERSION={}", app_version());
    tauri_build::build()
}

fn app_version() -> String {
    let output = Command::new("powershell")
        .args([
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            "../../../scripts/version.ps1",
        ])
        .output();

    match output {
        Ok(o) if o.status.success() => String::from_utf8_lossy(&o.stdout)
            .lines()
            .next()
            .unwrap_or("0.0.0-unknown")
            .trim()
            .to_string(),
        _ => "0.0.0-unknown".to_string(),
    }
}
