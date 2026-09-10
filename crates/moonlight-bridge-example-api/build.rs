fn main() -> Result<(), Box<dyn std::error::Error>> {
    moonlight_bridge_codegen::compile_rust_api(moonlight_bridge_codegen::RustBuildConfig {
        protos: vec!["../../proto/moonlight/bridge/example/v1/echo.proto".into()],
        includes: vec!["../../proto".into()],
        schema_lock: "../../proto/schema.lock".into(),
        generated_services_name: "moonlight_bridge_services.rs".into(),
    })?;
    Ok(())
}
