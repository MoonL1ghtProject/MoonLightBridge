use moonlight_bridge_codegen::{Idempotency, descriptor_policies, generate_java, generate_rust};
use std::{fs, path::PathBuf, process::Command};

#[test]
fn valid_method_policy_is_loaded_from_descriptor_extension() {
    let descriptor = compile_fixture(
        r#"
syntax = "proto3";
package policy.test;
import "moonlight/bridge/options/v1/options.proto";
service TestService {
  rpc Read(Input) returns (Output) {
    option (moonlight.bridge.options.v1.rpc_policy) = {
      timeout_ms: 1500
      retry: { max_attempts: 3 initial_backoff_ms: 20 max_backoff_ms: 200 multiplier_milli: 2000 }
      idempotency: IDEMPOTENCY_READ_ONLY
      max_request_bytes: 4096
      max_response_bytes: 8192
      required_scopes: "player.read"
      compression: COMPRESSION_MODE_PREFER
      trace_sample_per_million: 25000
    };
  }
}
message Input {}
message Output {}
"#,
    );
    let policies = descriptor_policies(&descriptor).unwrap();
    let policy = &policies["policy.test.TestService/Read"];
    assert_eq!(policy.timeout_ms, 1500);
    assert_eq!(policy.retry.max_attempts, 3);
    assert_eq!(policy.idempotency, Idempotency::ReadOnly);
    assert_eq!(policy.required_scopes, ["player.read"]);
    assert_eq!(policy.trace_sample_per_million, 25_000);
}

#[test]
fn generated_bindings_embed_and_apply_method_policy() {
    let descriptor = compile_fixture(
        r#"
syntax = "proto3";
package policy.test;
option java_package = "policy.test";
option java_multiple_files = true;
import "moonlight/bridge/options/v1/options.proto";
service TestService {
  rpc Read(Input) returns (Output) {
    option (moonlight.bridge.options.v1.rpc_policy) = {
      timeout_ms: 1500
      retry: { max_attempts: 3 initial_backoff_ms: 20 max_backoff_ms: 200 multiplier_milli: 2000 }
      idempotency: IDEMPOTENCY_READ_ONLY
      max_request_bytes: 4096
      max_response_bytes: 8192
      required_scopes: "player.read"
      compression: COMPRESSION_MODE_PREFER
      trace_sample_per_million: 25000
    };
  }
}
message Input {}
message Output {}
"#,
    );
    let directory = descriptor.parent().unwrap();
    let rust_output = directory.join("services.rs");
    let java_output = directory.join("java");
    generate_rust(&descriptor, &rust_output).unwrap();
    generate_java(&descriptor, &java_output).unwrap();

    let rust = fs::read_to_string(rust_output).unwrap();
    assert!(rust.contains("pub const TEST_SERVICE_READ_POLICY: RpcPolicy"));
    assert!(rust.contains("body.len() > TEST_SERVICE_READ_POLICY.max_request_bytes"));

    let java = fs::read_to_string(java_output.join("policy/test/TestServiceClient.java")).unwrap();
    assert!(java.contains("public static final RpcPolicy READ_POLICY"));
    assert!(java.contains("channel.request(READ_METHOD_ID, payload, callDeadline, READ_POLICY)"));
}

#[test]
fn retrying_mutation_without_idempotency_is_rejected() {
    assert_policy_error(
        "retry: { max_attempts: 2 }",
        "retries require read-only, idempotent, or idempotency-key semantics",
    );
}

#[test]
fn streaming_retry_is_rejected() {
    let descriptor = compile_fixture(&fixture_rpc(
        "returns (stream Output)",
        "retry: { max_attempts: 2 } idempotency: IDEMPOTENCY_IDEMPOTENT",
    ));
    let error = descriptor_policies(&descriptor).unwrap_err().to_string();
    assert!(
        error.contains("streaming RPCs cannot enable transparent retry"),
        "{error}"
    );
}

#[test]
fn invalid_ranges_and_scope_syntax_are_rejected() {
    assert_policy_error("timeout_ms: 0", "timeout_ms must be between");
    assert_policy_error(
        "required_scopes: \"Player Admin\"",
        "invalid required scope",
    );
    assert_policy_error(
        "trace_sample_per_million: 1000001",
        "trace_sample_per_million",
    );
}

fn assert_policy_error(options: &str, expected: &str) {
    let descriptor = compile_fixture(&fixture_rpc("returns (Output)", options));
    let error = descriptor_policies(&descriptor).unwrap_err().to_string();
    assert!(error.contains(expected), "{error}");
}

fn fixture_rpc(response: &str, options: &str) -> String {
    format!(
        r#"
syntax = "proto3";
package policy.test;
import "moonlight/bridge/options/v1/options.proto";
service TestService {{
  rpc Mutate(Input) {response} {{
    option (moonlight.bridge.options.v1.rpc_policy) = {{ {options} }};
  }}
}}
message Input {{}}
message Output {{}}
"#
    )
}

fn compile_fixture(source: &str) -> PathBuf {
    let unique = format!(
        "moonlight-policy-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    );
    let directory = std::env::temp_dir().join(unique);
    fs::create_dir_all(&directory).unwrap();
    let proto = directory.join("fixture.proto");
    let descriptor = directory.join("fixture.pb");
    fs::write(&proto, source).unwrap();
    let repository_proto = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../proto");
    let output = Command::new("protoc")
        .arg(format!("--proto_path={}", directory.display()))
        .arg(format!("--proto_path={}", repository_proto.display()))
        .arg("--include_imports")
        .arg(format!("--descriptor_set_out={}", descriptor.display()))
        .arg(&proto)
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "protoc failed: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    descriptor
}
