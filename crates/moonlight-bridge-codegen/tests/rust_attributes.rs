use std::{
    fs,
    path::PathBuf,
    process::Command,
    time::{SystemTime, UNIX_EPOCH},
};

#[test]
fn rust_contract_attributes_are_valid_code_for_rust_analyzer_and_rustc() {
    let directory = temporary_directory("rust-attributes");
    let contract_crate = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../moonlight-bridge-contract")
        .canonicalize()
        .expect("moonlight-bridge-contract crate must exist");
    fs::create_dir_all(directory.join("src")).unwrap();
    fs::write(
        directory.join("Cargo.toml"),
        format!(
            r#"[package]
name = "moonlight-attribute-fixture"
version = "0.0.0"
edition = "2024"

[dependencies]
moonlight = {{ package = "moonlight-bridge-contract", path = {:?} }}
"#,
            contract_crate
        ),
    )
    .unwrap();
    fs::write(
        directory.join("src/lib.rs"),
        r#"#![deny(warnings)]

#[moonlight::contract(package = "example.v1", java_package = "example.v1")]
mod api {
    #[moonlight::message]
    struct Request { value: String }

    #[moonlight::message]
    struct Response { value: String }

    #[moonlight::enumeration]
    enum State { Unknown, Ready }

    #[moonlight::service]
    trait ExampleService {
        #[moonlight::rpc(timeout_ms = 1000, idempotency = "idempotent")]
        async fn call(request: Request) -> Response;
    }
}
"#,
    )
    .unwrap();
    let output = Command::new("cargo")
        .args(["check", "--quiet"])
        .current_dir(&directory)
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "annotated contract must be valid Rust:\n{}",
        String::from_utf8_lossy(&output.stderr)
    );
}

fn temporary_directory(label: &str) -> PathBuf {
    let nonce = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let directory =
        std::env::temp_dir().join(format!("moonlight-{label}-{}-{nonce}", std::process::id()));
    fs::create_dir_all(&directory).unwrap();
    directory
}
