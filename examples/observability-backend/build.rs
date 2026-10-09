fn main() -> Result<(), Box<dyn std::error::Error>> {
    moonlight_bridge_codegen::compile_rust_source_api(
        moonlight_bridge_codegen::RustSourceBuildConfig {
            source: "src/contract.rs".into(),
            additional_protos: Vec::new(),
            includes: vec!["../../proto".into()],
            schema_lock: "../observability/schema.lock".into(),
            generated_services_name: "moonlight_bridge_services.rs".into(),
        },
    )?;
    Ok(())
}
