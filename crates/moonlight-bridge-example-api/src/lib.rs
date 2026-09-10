pub mod model {
    include!(concat!(env!("OUT_DIR"), "/moonlight.bridge.example.v1.rs"));
}

include!(concat!(env!("OUT_DIR"), "/moonlight_bridge_services.rs"));

#[cfg(test)]
mod tests {
    #[test]
    fn generated_method_id_is_stable() {
        assert_eq!(
            moonlight_bridge_codegen::method_id("moonlight.bridge.example.v1.EchoService/Echo"),
            0x237D_FACC
        );
    }
}
