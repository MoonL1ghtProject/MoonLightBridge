use moonlight_bridge_codegen::{RustSourceConfig, generate_proto_from_rust_source};
use std::{
    fs,
    path::PathBuf,
    process::Command,
    time::{SystemTime, UNIX_EPOCH},
};

#[test]
fn annotated_rust_contract_generates_proto_with_locked_tags_and_policy() {
    let directory = temporary_directory("rust-source");
    let source = directory.join("contract.rs");
    let output = directory.join("contract.proto");
    let lock = directory.join("schema.lock");
    fs::write(
        &source,
        r#"
#[moonlight::contract(package = "moonlight.example.v1", java_package = "dev.example.v1")]
mod contract {
    #[moonlight::message]
    pub struct EchoRequest {
        pub message: String,
        pub attempts: Option<i32>,
        pub tags: Vec<String>,
    }

    #[moonlight::message]
    pub struct EchoResponse { pub message: String }

    #[moonlight::enumeration]
    pub enum DeliveryState { Unknown, Ready }

    #[moonlight::service]
    pub trait EchoService {
        #[moonlight::rpc(
            timeout_ms = 1500,
            max_attempts = 3,
            initial_backoff_ms = 10,
            max_backoff_ms = 100,
            multiplier_milli = 2000,
            idempotency = "idempotent",
            required_scope = "echo.invoke",
            compression = "prefer",
            trace_sample_per_million = 250000
        )]
        async fn echo(request: EchoRequest) -> EchoResponse;
    }
}
"#,
    )
    .unwrap();
    fs::write(&lock, r#"{
  "format": 2,
  "messages": {
    "moonlight.example.v1.EchoRequest": {
      "fields": {
        "7": {"name":"message","number":7,"label":1,"kind":9,"type_name":"","oneof_index":null,"proto3_optional":false}
      },
      "reserved_ranges": [],
      "reserved_names": []
    }
  },
  "enums": {
    "moonlight.example.v1.DeliveryState": {
      "values": {"0":"UNKNOWN","4":"READY","7":"LEGACY"},
      "reserved_ranges": [{"start":9,"end":10}],
      "reserved_names": ["DEPRECATED"]
    }
  },
  "services": {}
}
"#).unwrap();

    generate_proto_from_rust_source(RustSourceConfig {
        source,
        output: output.clone(),
        schema_lock: Some(lock),
    })
    .unwrap();

    let proto = fs::read_to_string(output).unwrap();
    assert!(proto.contains("package moonlight.example.v1;"), "{proto}");
    assert!(
        proto.contains("option java_package = \"dev.example.v1\";"),
        "{proto}"
    );
    assert!(proto.contains("string message = 7;"), "{proto}");
    assert!(proto.contains("optional int32 attempts = 1;"), "{proto}");
    assert!(proto.contains("repeated string tags = 2;"), "{proto}");
    assert!(proto.contains("UNKNOWN = 0;"), "{proto}");
    assert!(proto.contains("READY = 4;"), "{proto}");
    assert!(proto.contains("reserved 7;"), "{proto}");
    assert!(proto.contains("reserved \"LEGACY\";"), "{proto}");
    assert!(proto.contains("reserved 9;"), "{proto}");
    assert!(proto.contains("reserved \"DEPRECATED\";"), "{proto}");
    assert!(
        proto.contains("rpc Echo(EchoRequest) returns (EchoResponse)"),
        "{proto}"
    );
    assert!(proto.contains("timeout_ms: 1500"), "{proto}");
    assert!(
        proto.contains("required_scopes: \"echo.invoke\""),
        "{proto}"
    );
    assert!(
        proto.contains("compression: COMPRESSION_MODE_PREFER"),
        "{proto}"
    );
    let descriptor = directory.join("contract.pb");
    let repository_proto = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../proto");
    let status = Command::new("protoc")
        .arg(format!("--proto_path={}", directory.display()))
        .arg(format!("--proto_path={}", repository_proto.display()))
        .arg(format!("--descriptor_set_out={}", descriptor.display()))
        .arg("--include_imports")
        .arg(directory.join("contract.proto"))
        .status()
        .unwrap();
    assert!(status.success(), "generated Protobuf must compile: {proto}");
}

#[test]
fn removed_locked_field_is_reserved_and_unsupported_type_points_to_field() {
    let directory = temporary_directory("rust-source-errors");
    let source = directory.join("contract.rs");
    let output = directory.join("contract.proto");
    let lock = directory.join("schema.lock");
    fs::write(
        &source,
        r#"
#[moonlight::contract(package = "example.v1", java_package = "example.v1")]
mod contract {
    #[moonlight::message]
    struct Player { id: String }
}
"#,
    )
    .unwrap();
    fs::write(&lock, r#"{
  "format": 2,
  "messages": {
    "example.v1.Player": {
      "fields": {
        "1": {"name":"id","number":1,"label":1,"kind":9,"type_name":"","oneof_index":null,"proto3_optional":false},
        "4": {"name":"old_rank","number":4,"label":1,"kind":5,"type_name":"","oneof_index":null,"proto3_optional":false}
      },
      "reserved_ranges": [],
      "reserved_names": []
    }
  },
  "enums": {},
  "services": {}
}
"#).unwrap();
    generate_proto_from_rust_source(RustSourceConfig {
        source: source.clone(),
        output: output.clone(),
        schema_lock: Some(lock.clone()),
    })
    .unwrap();
    let proto = fs::read_to_string(output).unwrap();
    assert!(proto.contains("reserved 4;"), "{proto}");
    assert!(proto.contains("reserved \"old_rank\";"), "{proto}");

    fs::write(
        &source,
        r#"
#[moonlight::contract(package = "example.v1", java_package = "example.v1")]
mod contract {
    #[moonlight::message]
    struct Player { created_at: std::time::Instant }
}
"#,
    )
    .unwrap();
    let error = generate_proto_from_rust_source(RustSourceConfig {
        source,
        output: directory.join("invalid.proto"),
        schema_lock: Some(lock),
    })
    .unwrap_err();
    assert!(error.to_string().contains("Player.created_at"), "{error}");
}

#[test]
fn source_cli_exports_rust_owned_contract_for_other_builds() {
    let directory = temporary_directory("rust-source-cli");
    let source = directory.join("contract.rs");
    let output = directory.join("contract.proto");
    fs::write(
        &source,
        r#"
#[moonlight::contract(package = "example.cli.v1", java_package = "example.cli.v1")]
mod contract {
    #[moonlight::message]
    struct Request { value: String }
}
"#,
    )
    .unwrap();
    let status = Command::new(env!("CARGO_BIN_EXE_moonlight-bridge-codegen"))
        .args(["source", "rust"])
        .arg(&source)
        .arg(&output)
        .status()
        .unwrap();
    assert!(status.success());
    assert!(
        fs::read_to_string(output)
            .unwrap()
            .contains("message Request")
    );
}

#[test]
fn generated_contract_can_be_extended_by_handwritten_proto_and_locked() {
    let directory = temporary_directory("rust-source-mixed");
    let source = directory.join("contract.rs");
    let generated = directory.join("contract.proto");
    let extension = directory.join("extension.proto");
    let descriptor = directory.join("mixed.pb");
    let lock = directory.join("schema.lock");
    fs::write(
        &source,
        r#"
#[moonlight::contract(package = "example.mixed.v1", java_package = "example.mixed.v1")]
mod contract {
    #[moonlight::message]
    struct Request { value: String }
}
"#,
    )
    .unwrap();
    fs::write(
        &extension,
        r#"syntax = "proto3";
package example.mixed.v1;
import "contract.proto";
option java_package = "example.mixed.v1";
option java_multiple_files = true;
message Envelope {
  Request request = 1;
  string handwritten_note = 2;
}
"#,
    )
    .unwrap();
    generate_proto_from_rust_source(RustSourceConfig {
        source,
        output: generated,
        schema_lock: None,
    })
    .unwrap();
    let protoc = Command::new("protoc")
        .arg(format!("--proto_path={}", directory.display()))
        .arg(format!("--descriptor_set_out={}", descriptor.display()))
        .arg("--include_imports")
        .arg(&extension)
        .status()
        .unwrap();
    assert!(
        protoc.success(),
        "generated + handwritten Protobuf must compile"
    );
    let update = Command::new(env!("CARGO_BIN_EXE_moonlight-bridge-codegen"))
        .arg("lock")
        .arg(&descriptor)
        .arg(&lock)
        .arg("--update")
        .status()
        .unwrap();
    assert!(update.success(), "mixed schema lock must be writable");
    let check = Command::new(env!("CARGO_BIN_EXE_moonlight-bridge-codegen"))
        .arg("lock")
        .arg(&descriptor)
        .arg(&lock)
        .arg("--check")
        .status()
        .unwrap();
    assert!(check.success(), "mixed schema lock must remain compatible");
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
