pub mod model {
    include!(concat!(env!("OUT_DIR"), "/expj.example.v1.rs"));
}

include!(concat!(env!("OUT_DIR"), "/expj_services.rs"));

#[cfg(test)]
mod tests {
    #[test]
    fn generated_method_id_is_stable() {
        assert_eq!(
            expj_codegen::method_id("expj.example.v1.EchoService/Echo"),
            0xC17A_0311
        );
    }
}
