fn main() -> Result<(), Box<dyn std::error::Error>> {
    moonlight_bridge_codegen::compile_rust_api(moonlight_bridge_codegen::RustBuildConfig {
        protos: vec!["../observability/proto/monitoring.proto".into()],
        includes: vec!["../observability/proto".into()],
        schema_lock: "../observability/schema.lock".into(),
        generated_services_name: "moonlight_bridge_services.rs".into(),
    })?;
    Ok(())
}
